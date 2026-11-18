package ITubeBackend.sharding;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one place catalog code chooses a database. Three ways to route:
 * <ul>
 *   <li>{@link #onShardOf(Long, Supplier)} - by channel or video id (the shard is in the id);</li>
 *   <li>{@link #onOwnerShard(Long, Supplier)} - by creator (their channels, videos and uploads are together);</li>
 *   <li>{@link #scatter(Function)} - ask every database in parallel and merge (home feed, search). Each shard has a
 *       time limit; a shard that is down or slow is skipped and the rest still answer (a partial result beats an
 *       error page), counted in the metric itube.catalog.scatter.partial.</li>
 * </ul>
 */
@Component
public class Shards {
    private static final Logger log = LoggerFactory.getLogger(Shards.class);

    private final ShardMap shardMap;
    private final OwnerDirectory directory;
    private final Duration scatterTimeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Counter partialResults;

    public Shards(ShardMap shardMap, OwnerDirectory directory, ShardingProperties properties, MeterRegistry meters) {
        this.shardMap = shardMap;
        this.directory = directory;
        this.scatterTimeout = properties.scatterTimeout();
        this.partialResults = Counter.builder("itube.catalog.scatter.partial")
                .description("Scatter-gather queries answered without one or more shards").register(meters);
    }

    /** Physical shard of a channel or video id, or empty if the id can't exist. */
    public OptionalInt physicalOf(Long id) {
        int logical = ShardIds.logicalShardOf(id);
        return logical < 0 ? OptionalInt.empty() : OptionalInt.of(shardMap.physicalOf(logical));
    }

    /** Runs on the shard holding this channel or video; an impossible id is "not found". */
    public <T> T onShardOf(Long id, Supplier<T> work) {
        OptionalInt physical = physicalOf(id);
        if (physical.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        return ShardContext.on(physical.getAsInt(), work);
    }

    public void runOnShardOf(Long id, Runnable work) {
        onShardOf(id, () -> {
            work.run();
            return null;
        });
    }

    /** Runs on the creator's shard, assigning one if this is their first channel (use for writes). */
    public <T> T onOwnerShard(Long userId, Supplier<T> work) {
        return ShardContext.on(shardMap.physicalOf(directory.assign(userId)), work);
    }

    /** Runs on the creator's shard, or returns {@code ifNoChannel} for users who never had a channel (reads). */
    public <T> T onExistingOwnerShard(Long userId, Supplier<T> work, Supplier<T> ifNoChannel) {
        OptionalInt logical = directory.find(userId);
        return logical.isEmpty() ? ifNoChannel.get() : ShardContext.on(shardMap.physicalOf(logical.getAsInt()), work);
    }

    /** The creator's logical shard (assigning one if needed): new channels get ids on it. */
    public int logicalShardForOwner(Long userId) {
        return directory.assign(userId);
    }

    /** Ids grouped by the physical shard that holds them; impossible ids are dropped. */
    public Map<Integer, List<Long>> groupByShard(Collection<Long> ids) {
        Map<Integer, List<Long>> grouped = new LinkedHashMap<>();
        for (Long id : ids) {
            physicalOf(id).ifPresent(shard -> grouped.computeIfAbsent(shard, s -> new ArrayList<>()).add(id));
        }
        return grouped;
    }

    /** Runs {@code perShard} on every database in parallel and concatenates the results (unordered). */
    public <T> List<T> scatter(Function<Integer, List<T>> perShard) {
        return scatterTo(shardMap.physicalShards(), perShard);
    }

    /** Like {@link #scatter} but only on the given shards (e.g. the ones some ids live on). */
    public <T> List<T> scatterTo(Collection<Integer> physicalShards, Function<Integer, List<T>> perShard) {
        if (physicalShards.size() == 1) {
            Integer only = physicalShards.iterator().next();
            return ShardContext.on(only, () -> perShard.apply(only));
        }
        Map<Integer, Future<List<T>>> futures = new LinkedHashMap<>();
        for (Integer shard : physicalShards) {
            futures.put(shard, executor.submit(() -> ShardContext.on(shard, () -> perShard.apply(shard))));
        }
        long deadline = System.nanoTime() + scatterTimeout.toNanos();
        List<T> merged = new ArrayList<>();
        boolean partial = false;
        for (Map.Entry<Integer, Future<List<T>>> entry : futures.entrySet()) {
            try {
                merged.addAll(entry.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (TimeoutException | ExecutionException exception) {
                entry.getValue().cancel(true);
                partial = true;
                log.warn("Catalog shard {} did not answer a scatter query: {}", entry.getKey(),
                        exception instanceof ExecutionException ? exception.getCause().toString() : "timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while querying catalog shards", exception);
            }
        }
        if (partial) partialResults.increment();
        return merged;
    }
}
