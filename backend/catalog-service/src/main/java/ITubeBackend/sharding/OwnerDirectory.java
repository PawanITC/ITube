package ITubeBackend.sharding;

import ITubeBackend.common.cache.CacheConfig;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Which logical shard each creator lives on (table catalog.owner_shard on physical shard 0).
 * <p>
 * Why a directory instead of just hashing the user id: creators who existed before sharding live on logical
 * shard 0, wherever their hash would point. The directory records that; new creators get their hash.
 * Entries never change once written, so they are cached in Caffeine for an hour and the directory is read about
 * once per creator per pod, not once per request.
 */
@Component
public class OwnerDirectory {
    private static final Logger log = LoggerFactory.getLogger(OwnerDirectory.class);

    private final JdbcTemplate jdbc;
    private final Cache<Long, Integer> cache;
    private final boolean backfill;

    public OwnerDirectory(ShardRoutingDataSource dataSource, CacheManager cacheManager,
                          @Value("${itube.sharding.backfill-directory:true}") boolean backfill) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.cache = CacheConfig.nativeCache(cacheManager, "owner-shard");
        this.backfill = backfill;
    }

    /** The creator's logical shard, if they have ever had a channel. */
    public OptionalInt find(long userId) {
        Integer cached = cache.getIfPresent(userId);
        if (cached != null) return OptionalInt.of(cached);
        List<Integer> found = ShardContext.on(0, () -> jdbc.queryForList(
                "SELECT logical_shard FROM catalog.owner_shard WHERE user_id = ?", Integer.class, userId));
        if (found.isEmpty()) return OptionalInt.empty();
        cache.put(userId, found.getFirst());
        return OptionalInt.of(found.getFirst());
    }

    /** The creator's logical shard, choosing one (by hash) the first time. Safe to race: the first insert wins. */
    public int assign(long userId) {
        OptionalInt existing = find(userId);
        if (existing.isPresent()) return existing.getAsInt();
        int chosen = ShardIds.logicalShardForNewOwner(userId);
        try {
            ShardContext.run(0, () -> jdbc.update(
                    "INSERT INTO catalog.owner_shard (user_id, logical_shard) VALUES (?, ?)", userId, chosen));
        } catch (DataIntegrityViolationException raced) {
            cache.invalidate(userId);
            return find(userId).orElseThrow();
        }
        cache.put(userId, chosen);
        return chosen;
    }

    /**
     * Creators from before sharding: record the shard their existing channels are on (always logical 0 for old
     * ids). Idempotent; turn off with itube.sharding.backfill-directory=false once every environment has run it.
     */
    @EventListener(ApplicationReadyEvent.class)
    void backfillExistingOwners() {
        if (!backfill) return;
        List<Map<String, Object>> owners = ShardContext.on(0, () -> jdbc.queryForList(
                "SELECT c.user_id, MIN(c.channel_id) AS channel_id FROM catalog.channel c "
                        + "WHERE NOT EXISTS (SELECT 1 FROM catalog.owner_shard o WHERE o.user_id = c.user_id) "
                        + "GROUP BY c.user_id"));
        int added = 0;
        for (Map<String, Object> owner : owners) {
            long userId = ((Number) owner.get("user_id")).longValue();
            int logical = Math.max(0, ShardIds.logicalShardOf(((Number) owner.get("channel_id")).longValue()));
            try {
                ShardContext.run(0, () -> jdbc.update(
                        "INSERT INTO catalog.owner_shard (user_id, logical_shard) VALUES (?, ?)", userId, logical));
                added++;
            } catch (DataIntegrityViolationException alreadyThere) {
                // another instance backfilled it first
            }
        }
        if (added > 0) log.info("Shard directory: recorded {} creators from before sharding", added);
    }
}
