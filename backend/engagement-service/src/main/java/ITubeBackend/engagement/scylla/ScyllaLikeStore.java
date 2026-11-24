package ITubeBackend.engagement.scylla;

import ITubeBackend.engagement.LikeStore;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;

import java.time.Instant;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * like_lookup is the source of truth for "is it liked" and is only changed with lightweight transactions,
 * so concurrent like/unlike requests can't create duplicates. likes_by_user and likes_by_video are
 * query tables written in a logged batch after the lookup row changes.
 */
public class ScyllaLikeStore implements LikeStore {
    private final CqlSession session;
    private final PreparedStatement insertLookup;
    private final PreparedStatement selectLookup;
    private final PreparedStatement deleteLookup;
    private final PreparedStatement insertByUser;
    private final PreparedStatement deleteByUser;
    private final PreparedStatement selectByUser;
    private final PreparedStatement insertByVideo;
    private final PreparedStatement deleteByVideo;
    private final PreparedStatement selectByVideo;
    private final PreparedStatement deleteVideoPartition;

    public ScyllaLikeStore(CqlSession session, String keyspace) {
        this.session = session;
        insertLookup = session.prepare("INSERT INTO " + keyspace + ".like_lookup (user_id, video_id, liked_at) VALUES (?, ?, ?) IF NOT EXISTS");
        selectLookup = session.prepare("SELECT liked_at FROM " + keyspace + ".like_lookup WHERE user_id = ? AND video_id = ?");
        deleteLookup = session.prepare("DELETE FROM " + keyspace + ".like_lookup WHERE user_id = ? AND video_id = ? IF EXISTS");
        insertByUser = session.prepare("INSERT INTO " + keyspace + ".likes_by_user (user_id, liked_at, video_id) VALUES (?, ?, ?)");
        deleteByUser = session.prepare("DELETE FROM " + keyspace + ".likes_by_user WHERE user_id = ? AND liked_at = ? AND video_id = ?");
        selectByUser = session.prepare("SELECT video_id, liked_at FROM " + keyspace + ".likes_by_user WHERE user_id = ?");
        insertByVideo = session.prepare("INSERT INTO " + keyspace + ".likes_by_video (video_id, user_id) VALUES (?, ?)");
        deleteByVideo = session.prepare("DELETE FROM " + keyspace + ".likes_by_video WHERE video_id = ? AND user_id = ?");
        selectByVideo = session.prepare("SELECT user_id FROM " + keyspace + ".likes_by_video WHERE video_id = ?");
        deleteVideoPartition = session.prepare("DELETE FROM " + keyspace + ".likes_by_video WHERE video_id = ?");
    }

    @Override
    public java.util.Optional<Like> find(long userId, long videoId) {
        Row row = session.execute(selectLookup.bind(userId, videoId)
                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL)).one();
        return row == null ? java.util.Optional.empty()
                : java.util.Optional.of(new Like(userId, videoId, row.getInstant("liked_at")));
    }

    @Override
    public boolean likeIfAbsent(long userId, long videoId, Instant likedAt) {
        if (!session.execute(insertLookup.bind(userId, videoId, likedAt)).wasApplied()) return false;
        session.execute(BatchStatement.newInstance(DefaultBatchType.LOGGED,
                insertByUser.bind(userId, likedAt, videoId),
                insertByVideo.bind(videoId, userId)));
        return true;
    }

    @Override
    public void unlike(long userId, long videoId) {
        // Serial read: sees the latest lightweight-transaction write, not a possibly stale replica
        Row lookup = session.execute(selectLookup.bind(userId, videoId)
                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL)).one();
        if (lookup == null) return;
        if (!session.execute(deleteLookup.bind(userId, videoId)).wasApplied()) return;
        session.execute(BatchStatement.newInstance(DefaultBatchType.LOGGED,
                deleteByUser.bind(userId, lookup.getInstant("liked_at"), videoId),
                deleteByVideo.bind(videoId, userId)));
    }

    @Override
    public boolean isLiked(long userId, long videoId) {
        return session.execute(selectLookup.bind(userId, videoId)).one() != null;
    }

    @Override
    public Stream<Like> newestFirst(long userId) {
        // The driver fetches further pages only as the stream is consumed
        var rows = session.execute(selectByUser.bind(userId).setPageSize(100));
        return StreamSupport.stream(rows.spliterator(), false)
                .map(row -> new Like(userId, row.getLong("video_id"), row.getInstant("liked_at")));
    }

    @Override
    public void deleteAllForVideo(long videoId) {
        for (Row row : session.execute(selectByVideo.bind(videoId).setPageSize(500))) {
            unlike(row.getLong("user_id"), videoId);
        }
        session.execute(deleteVideoPartition.bind(videoId));
    }
}
