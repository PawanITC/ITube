package ITubeBackend.engagement;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comments per video, newest first. The author's display name is copied in when the comment is written,
 * so reading comments never touches the identity database.
 */
public interface CommentStore {
    Comment add(long videoId, long userId, String authorName, String content, Instant createdAt);

    /** Writes a comment with a known id (an upsert), e.g. when copying old data. */
    void put(Comment comment);

    List<Comment> newestFirst(long videoId, int limit);

    void deleteAllForVideo(long videoId);

    record Comment(UUID id, long videoId, long userId, String authorName, String content, Instant createdAt) {
    }
}
