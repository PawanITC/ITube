package ITubeBackend.migration;

import ITubeBackend.engagement.CommentStore;
import ITubeBackend.engagement.LikeStore;
import ITubeBackend.engagement.TimeUuids;
import ITubeBackend.engagement.WatchHistoryStore;
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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Copies data written before the database split into its new home, then gets out of the way:
 * <ul>
 *   <li>video, thumbnail and rendition bytes in catalog.* -> object storage (the byte column is emptied)</li>
 *   <li>legacy_engagement.watch_history / liked_video / video_comment -> ScyllaDB (the table is then
 *       renamed *_copied; drop it by hand once you're happy)</li>
 * </ul>
 * Media rows are claimed one at a time with FOR UPDATE SKIP LOCKED, so several instances can run it at
 * once. Engagement copies are upserts with deterministic ids, so a crash halfway is fixed by re-running.
 */
@Component
@ConditionalOnProperty(name = "itube.migration.legacy-data", havingValue = "true")
public class LegacyDataMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyDataMigration.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectStorage storage;
    private final WatchHistoryStore historyStore;
    private final LikeStore likeStore;
    private final CommentStore commentStore;

    public LegacyDataMigration(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectStorage storage,
                               WatchHistoryStore historyStore, LikeStore likeStore, CommentStore commentStore) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.storage = storage;
        this.historyStore = historyStore;
        this.likeStore = likeStore;
        this.commentStore = commentStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int videos = drain(this::moveOneVideoFile);
            int thumbnails = drain(this::moveOneThumbnail);
            int renditions = drain(this::moveOneRendition);
            if (videos + thumbnails + renditions > 0) {
                log.info("Moved {} video files, {} thumbnails and {} renditions to object storage", videos, thumbnails, renditions);
            }
            copyHistory();
            copyLikes();
            copyComments();
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

    private void copyHistory() {
        if (!tableExists("watch_history")) return;
        long[] copied = {0};
        stream("SELECT id, user_id, video_id, watched_at FROM legacy_engagement.watch_history", rs -> {
            Long userId = parseUserId(rs.getString("user_id"));
            if (userId == null) return;
            Instant watchedAt = rs.getTimestamp("watched_at").toInstant();
            historyStore.put(new WatchHistoryStore.HistoryEntry(TimeUuids.at(watchedAt, rs.getLong("id")),
                    userId, rs.getLong("video_id"), watchedAt));
            copied[0]++;
        });
        markCopied("watch_history", copied[0]);
    }

    private void copyLikes() {
        if (!tableExists("liked_video")) return;
        long[] copied = {0};
        stream("SELECT user_id, video_id, liked_at FROM legacy_engagement.liked_video", rs -> {
            likeStore.likeIfAbsent(rs.getLong("user_id"), rs.getLong("video_id"), rs.getTimestamp("liked_at").toInstant());
            copied[0]++;
        });
        markCopied("liked_video", copied[0]);
    }

    private void copyComments() {
        if (!tableExists("video_comment")) return;
        long[] copied = {0};
        // A one-off read across schemas to capture each author's name, as new comments do
        stream("SELECT c.comment_id, c.video_id, c.user_id, c.content, c.created_at, a.firstname, a.lastname "
                + "FROM legacy_engagement.video_comment c LEFT JOIN identity.accounts a ON a.user_id = c.user_id", rs -> {
            Instant createdAt = rs.getTimestamp("created_at").toInstant();
            String author = rs.getString("firstname") == null ? "Deleted account"
                    : rs.getString("firstname") + " " + rs.getString("lastname");
            commentStore.put(new CommentStore.Comment(TimeUuids.at(createdAt, rs.getLong("comment_id")),
                    rs.getLong("video_id"), rs.getLong("user_id"), author, rs.getString("content"), createdAt));
            copied[0]++;
        });
        markCopied("video_comment", copied[0]);
    }

    private interface RowHandler {
        void handle(ResultSet rs) throws SQLException;
    }

    /** Reads with a cursor (fetch size inside a transaction) so large tables aren't loaded into memory. */
    private void stream(String sql, RowHandler handler) {
        transactions.executeWithoutResult(status -> jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setFetchSize(1000);
            return statement;
        }, (ResultSet rs) -> handler.handle(rs)));
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                "legacy_engagement." + table));
    }

    private void markCopied(String table, long rows) {
        jdbc.execute("ALTER TABLE IF EXISTS legacy_engagement." + table + " RENAME TO " + table + "_copied");
        log.info("Copied {} rows from legacy_engagement.{} to ScyllaDB (old table renamed {}_copied)", rows, table, table);
    }

    private static Long parseUserId(String userId) {
        try {
            return Long.valueOf(userId.trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
