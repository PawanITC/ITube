package ITubeBackend.engagement.scylla;

import ITubeBackend.engagement.SavedVideoStore;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import java.time.Instant;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Follows Likes: a unique lookup row plus a table ordered for the library page. */
public class ScyllaSavedVideoStore implements SavedVideoStore {
    private final CqlSession session;
    private final PreparedStatement insertLookup, selectLookup, deleteLookup, insertByUser, deleteByUser,
            selectByUser, selectUserLookup, clearByUser;

    public ScyllaSavedVideoStore(CqlSession session, String keyspace) {
        this.session = session;
        insertLookup = session.prepare("INSERT INTO " + keyspace + ".saved_video_lookup (user_id, video_id, saved_at) VALUES (?, ?, ?) IF NOT EXISTS");
        selectLookup = session.prepare("SELECT saved_at FROM " + keyspace + ".saved_video_lookup WHERE user_id = ? AND video_id = ?");
        deleteLookup = session.prepare("DELETE FROM " + keyspace + ".saved_video_lookup WHERE user_id = ? AND video_id = ? IF saved_at = ?");
        insertByUser = session.prepare("INSERT INTO " + keyspace + ".saved_videos_by_user (user_id, saved_at, video_id) VALUES (?, ?, ?)");
        deleteByUser = session.prepare("DELETE FROM " + keyspace + ".saved_videos_by_user WHERE user_id = ? AND saved_at = ? AND video_id = ?");
        selectByUser = session.prepare("SELECT video_id, saved_at FROM " + keyspace + ".saved_videos_by_user WHERE user_id = ?");
        selectUserLookup = session.prepare("SELECT video_id FROM " + keyspace + ".saved_video_lookup WHERE user_id = ?");
        clearByUser = session.prepare("DELETE FROM " + keyspace + ".saved_videos_by_user WHERE user_id = ?");
    }

    @Override
    public java.util.Optional<SavedVideo> find(long userId, long videoId) {
        var row = session.execute(selectLookup.bind(userId, videoId)
                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL)).one();
        return row == null ? java.util.Optional.empty()
                : java.util.Optional.of(new SavedVideo(userId, videoId, row.getInstant("saved_at")));
    }

    @Override
    public boolean saveIfAbsent(long userId, long videoId, Instant savedAt) {
        if (!session.execute(insertLookup.bind(userId, videoId, savedAt)).wasApplied()) return false;
        session.execute(insertByUser.bind(userId, savedAt, videoId));
        return true;
    }

    @Override
    public boolean isSaved(long userId, long videoId) {
        return session.execute(selectLookup.bind(userId, videoId)).one() != null;
    }

    @Override
    public void remove(long userId, long videoId) {
        var row = session.execute(selectLookup.bind(userId, videoId)
                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL)).one();
        if (row == null) return;
        Instant savedAt = row.getInstant("saved_at");
        if (!session.execute(deleteLookup.bind(userId, videoId, savedAt)).wasApplied()) return;
        session.execute(deleteByUser.bind(userId, savedAt, videoId));
    }

    @Override
    public void clear(long userId) {
        // Compatibility/migration callers still use LWT saves. An ordinary partition delete can have
        // an older timestamp than the preceding Paxos write, so clear those lookup rows with LWT too.
        // Kafka uses ScyllaProjectionWriter's ordered, explicitly timestamped batches instead.
        for (var row : session.execute(selectUserLookup.bind(userId)
                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL))) remove(userId, row.getLong("video_id"));
        session.execute(clearByUser.bind(userId));
    }

    @Override
    public Stream<SavedVideo> newestFirst(long userId) {
        var rows = session.execute(selectByUser.bind(userId).setPageSize(100));
        return StreamSupport.stream(rows.spliterator(), false)
                .map(row -> new SavedVideo(userId, row.getLong("video_id"), row.getInstant("saved_at")));
    }
}
