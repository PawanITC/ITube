package ITubeBackend.migration;

import ITubeBackend.sharding.ShardContext;
import ITubeBackend.sharding.ShardRoutingDataSource;
import ITubeBackend.storage.MediaKeys;
import ITubeBackend.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Moves media bytes written before the database split into object storage, then gets out of the way:
 * video, thumbnail and rendition bytes in catalog.* -> object storage (the byte column is emptied).
 * Old rows all live on physical shard 0 (the original database), so that's the only shard it looks at.
 * (The engagement half - history, likes, comments to ScyllaDB - is LegacyEngagementMigration in engagement-service.)
 * <p>
 * Rows are claimed one at a time with FOR UPDATE SKIP LOCKED, so several instances can run it at once.
 */
@Component
@ConditionalOnProperty(name = "itube.migration.legacy-data", havingValue = "true")
public class LegacyMediaMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyMediaMigration.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectStorage storage;

    public LegacyMediaMigration(ShardRoutingDataSource dataSource, TransactionTemplate transactions, ObjectStorage storage) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = transactions;
        this.storage = storage;
    }

    @Override
    public void run(ApplicationArguments args) {
        ShardContext.run(0, this::moveMedia);
    }

    private void moveMedia() {
        try {
            int videos = drain(this::moveOneVideoFile);
            int thumbnails = drain(this::moveOneThumbnail);
            int renditions = drain(this::moveOneRendition);
            if (videos + thumbnails + renditions > 0) {
                log.info("Moved {} video files, {} thumbnails and {} renditions to object storage", videos, thumbnails, renditions);
            }
        } catch (RuntimeException exception) {
            // Don't stop the app: old rows simply stay where they are until the next start
            log.error("Legacy data migration stopped early; it will resume on the next start", exception);
        }
    }

    private static int drain(java.util.function.BooleanSupplier step) {
        int moved = 0;
        while (step.getAsBoolean()) moved++;
        return moved;
    }

    private boolean moveOneVideoFile() {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT video_id, video, video_content_type FROM catalog.video "
                    + "WHERE video_key IS NULL AND video IS NOT NULL LIMIT 1 FOR UPDATE SKIP LOCKED");
            if (rows.isEmpty()) return false;
            Map<String, Object> row = rows.getFirst();
            byte[] bytes = (byte[]) row.get("video");
            String key = MediaKeys.source(MediaKeys.newVideoFolder());
            storage.put(key, bytes, (String) row.get("video_content_type"));
            jdbc.update("UPDATE catalog.video SET video_key = ?, video_size_bytes = ?, video = NULL WHERE video_id = ?",
                    key, (long) bytes.length, row.get("video_id"));
            return true;
        }));
    }

    private boolean moveOneThumbnail() {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT video_id, video_key, thumbnail, thumbnail_content_type "
                    + "FROM catalog.video WHERE thumbnail_key IS NULL AND thumbnail IS NOT NULL LIMIT 1 FOR UPDATE SKIP LOCKED");
            if (rows.isEmpty()) return false;
            Map<String, Object> row = rows.getFirst();
            String videoKey = (String) row.get("video_key");
            String key = MediaKeys.thumbnail(videoKey == null ? MediaKeys.newVideoFolder() : MediaKeys.folderOf(videoKey));
            storage.put(key, (byte[]) row.get("thumbnail"), (String) row.get("thumbnail_content_type"));
            jdbc.update("UPDATE catalog.video SET thumbnail_key = ?, thumbnail = NULL WHERE video_id = ?", key, row.get("video_id"));
            return true;
        }));
    }

    private boolean moveOneRendition() {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT r.video_id, r.quality, r.content, r.content_type, v.video_key "
                    + "FROM catalog.video_rendition r JOIN catalog.video v ON v.video_id = r.video_id "
                    + "WHERE r.storage_key IS NULL AND r.content IS NOT NULL LIMIT 1 FOR UPDATE OF r SKIP LOCKED");
            if (rows.isEmpty()) return false;
            Map<String, Object> row = rows.getFirst();
            String videoKey = (String) row.get("video_key");
            String folder = videoKey == null ? MediaKeys.newVideoFolder() : MediaKeys.folderOf(videoKey);
            String key = MediaKeys.rendition(folder, (String) row.get("quality"));
            byte[] bytes = (byte[]) row.get("content");
            storage.put(key, bytes, (String) row.get("content_type"));
            jdbc.update("UPDATE catalog.video_rendition SET storage_key = ?, size_bytes = ?, content = NULL "
                    + "WHERE video_id = ? AND quality = ?", key, (long) bytes.length, row.get("video_id"), row.get("quality"));
            return true;
        }));
    }

}
