package ITubeBackend.engagement.commands;

import ITubeBackend.service.playlists.PlaylistService;
import ITubeBackend.service.playlists.AutomaticPlaylistService;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import java.util.*;

/**
 * Kafka is the ordered writer for these endpoints. Logged batches repair ALL denormalized indexes on
 * redelivery, including a crash after only part of a batch applied. Explicit, persisted timestamps
 * prevent a delayed older retry from overwriting a newer change. Playlist rows retain their existing LWT.
 */
public class ScyllaProjectionWriter implements ProjectionWriter {
    private final CqlSession session;
    private final PlaylistService playlists;
    private final AutomaticPlaylistService automatic;
    private final Map<String, PreparedStatement> statements = new HashMap<>();
    public ScyllaProjectionWriter(CqlSession session, String keyspace, PlaylistService playlists, AutomaticPlaylistService automatic) {
        this.session = session; this.playlists = playlists;
        this.automatic = automatic;
        prepare("history.add", "INSERT INTO %s.watch_history_by_user (user_id, entry_id, video_id, watched_at) VALUES (?, ?, ?, ?)", keyspace);
        prepare("history.remove", "DELETE FROM %s.watch_history_by_user WHERE user_id = ? AND entry_id = ?", keyspace);
        prepare("history.clear", "DELETE FROM %s.watch_history_by_user WHERE user_id = ?", keyspace);
        prepare("like.lookup", "INSERT INTO %s.like_lookup (user_id, video_id, liked_at) VALUES (?, ?, ?)", keyspace);
        prepare("like.user", "INSERT INTO %s.likes_by_user (user_id, liked_at, video_id) VALUES (?, ?, ?)", keyspace);
        prepare("like.video", "INSERT INTO %s.likes_by_video (video_id, user_id) VALUES (?, ?)", keyspace);
        prepare("unlike.lookup", "DELETE FROM %s.like_lookup WHERE user_id = ? AND video_id = ?", keyspace);
        prepare("unlike.user", "DELETE FROM %s.likes_by_user WHERE user_id = ? AND liked_at = ? AND video_id = ?", keyspace);
        prepare("unlike.video", "DELETE FROM %s.likes_by_video WHERE video_id = ? AND user_id = ?", keyspace);
        prepare("save.lookup", "INSERT INTO %s.saved_video_lookup (user_id, video_id, saved_at) VALUES (?, ?, ?)", keyspace);
        prepare("save.user", "INSERT INTO %s.saved_videos_by_user (user_id, saved_at, video_id) VALUES (?, ?, ?)", keyspace);
        prepare("unsave.lookup", "DELETE FROM %s.saved_video_lookup WHERE user_id = ? AND video_id = ?", keyspace);
        prepare("unsave.user", "DELETE FROM %s.saved_videos_by_user WHERE user_id = ? AND saved_at = ? AND video_id = ?", keyspace);
        prepare("saved.clear.lookup", "DELETE FROM %s.saved_video_lookup WHERE user_id = ?", keyspace);
        prepare("saved.clear.user", "DELETE FROM %s.saved_videos_by_user WHERE user_id = ?", keyspace);
    }
    private void prepare(String name, String cql, String keyspace) { statements.put(name, session.prepare(cql.formatted(keyspace))); }
    private BoundStatement bind(String name, Object... values) { return statements.get(name).bind(values); }
    public void apply(EngagementCommand c, MutationPlan p) {
        long user = c.userId(); Long video = c.videoId();
        List<BatchableStatement<?>> writes = new ArrayList<>();
        switch (c.action()) {
            case HISTORY_ADD -> writes.add(bind("history.add", user, c.resourceId(), video, c.occurredAt()));
            case HISTORY_REMOVE -> writes.add(bind("history.remove", user, c.resourceId()));
            case HISTORY_CLEAR -> writes.add(bind("history.clear", user));
            case LIKE -> {
                writes.add(bind("like.lookup", user, video, p.itemTime()));
                writes.add(bind("like.user", user, p.itemTime(), video));
                writes.add(bind("like.video", video, user));
            }
            case UNLIKE -> {
                writes.add(bind("unlike.lookup", user, video));
                writes.add(bind("unlike.video", video, user));
                if (p.itemTime() != null) writes.add(bind("unlike.user", user, p.itemTime(), video));
            }
            case SAVE -> {
                writes.add(bind("save.lookup", user, video, p.itemTime()));
                writes.add(bind("save.user", user, p.itemTime(), video));
            }
            case UNSAVE -> {
                writes.add(bind("unsave.lookup", user, video));
                if (p.itemTime() != null) writes.add(bind("unsave.user", user, p.itemTime(), video));
            }
            case SAVED_CLEAR -> {
                writes.add(bind("saved.clear.lookup", user)); writes.add(bind("saved.clear.user", user));
            }
            case AUTOMATIC_REFRESH -> { automatic.applyRefresh(p.snapshot()); return; }
            default -> { playlists.applyPrepared(p.before(), p.after()); return; }
        }
        session.execute(BatchStatement.builder(DefaultBatchType.LOGGED).addStatements(writes)
                .setQueryTimestamp(p.writeMicros()).setIdempotence(true).build());
    }
}
