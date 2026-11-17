package ITubeBackend.engagement.scylla;

import ITubeBackend.engagement.CommentStore;
import ITubeBackend.engagement.TimeUuids;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

public class ScyllaCommentStore implements CommentStore {
    private final CqlSession session;
    private final PreparedStatement insert;
    private final PreparedStatement selectNewest;
    private final PreparedStatement deleteAll;

    public ScyllaCommentStore(CqlSession session, String keyspace) {
        this.session = session;
        String table = keyspace + ".comments_by_video";
        insert = session.prepare("INSERT INTO " + table
                + " (video_id, comment_id, user_id, author_name, content, created_at) VALUES (?, ?, ?, ?, ?, ?)");
        selectNewest = session.prepare("SELECT comment_id, user_id, author_name, content, created_at FROM " + table
                + " WHERE video_id = ? LIMIT ?");
        deleteAll = session.prepare("DELETE FROM " + table + " WHERE video_id = ?");
    }

    @Override
    public Comment add(long videoId, long userId, String authorName, String content, Instant createdAt) {
        UUID id = TimeUuids.at(createdAt);
        session.execute(insert.bind(videoId, id, userId, authorName, content, createdAt));
        return new Comment(id, videoId, userId, authorName, content, createdAt);
    }

    @Override
    public void put(Comment comment) {
        session.execute(insert.bind(comment.videoId(), comment.id(), comment.userId(), comment.authorName(),
                comment.content(), comment.createdAt()));
    }

    @Override
    public List<Comment> newestFirst(long videoId, int limit) {
        var rows = session.execute(selectNewest.bind(videoId, limit));
        return StreamSupport.stream(rows.spliterator(), false)
                .map(row -> new Comment(row.getUuid("comment_id"), videoId, row.getLong("user_id"),
                        row.getString("author_name"), row.getString("content"), row.getInstant("created_at")))
                .toList();
    }

    @Override
    public void deleteAllForVideo(long videoId) {
        session.execute(deleteAll.bind(videoId));
    }
}
