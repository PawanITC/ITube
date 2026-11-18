package ITubeBackend.migration;

import ITubeBackend.engagement.CommentStore;
import ITubeBackend.engagement.LikeStore;
import ITubeBackend.engagement.TimeUuids;
import ITubeBackend.engagement.WatchHistoryStore;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * One-off copy of engagement rows written before the database split (legacy_engagement.watch_history /
 * liked_video / video_comment in the old Postgres database) into ScyllaDB. Each table is renamed *_copied
 * when done; drop those by hand once you're happy. Copies are upserts with deterministic ids, so a crash
 * halfway is fixed by re-running. The video bytes half of the old migration lives in catalog-service.
 * <p>
 * Only runs with itube.migration.legacy-data=true. It opens its own small connection pool to the legacy
 * database and closes it when finished: engagement-service otherwise has no relational database.
 */
@Component
@ConditionalOnProperty(name = "itube.migration.legacy-data", havingValue = "true")
public class LegacyEngagementMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyEngagementMigration.class);

    private final Environment environment;
    private final WatchHistoryStore historyStore;
    private final LikeStore likeStore;
    private final CommentStore commentStore;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    public LegacyEngagementMigration(Environment environment, WatchHistoryStore historyStore, LikeStore likeStore,
                                     CommentStore commentStore) {
        this.environment = environment;
        this.historyStore = historyStore;
        this.likeStore = likeStore;
        this.commentStore = commentStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl(environment.getRequiredProperty("itube.migration.legacy-datasource.url"));
            dataSource.setUsername(environment.getProperty("itube.migration.legacy-datasource.username"));
            dataSource.setPassword(environment.getProperty("itube.migration.legacy-datasource.password"));
            dataSource.setMaximumPoolSize(2);
            dataSource.setPoolName("legacy-engagement-migration");
            jdbc = new JdbcTemplate(dataSource);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            copyHistory();
            copyLikes();
            copyComments();
        } catch (RuntimeException exception) {
            // Don't stop the app: old rows simply stay where they are until the next start
            log.error("Legacy engagement migration stopped early; it will resume on the next start", exception);
        }
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
