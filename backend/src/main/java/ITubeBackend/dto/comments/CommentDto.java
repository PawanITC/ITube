package ITubeBackend.dto.comments;

import java.time.OffsetDateTime;
import java.util.UUID;

/** commentId is a time-based UUID (the ScyllaDB clustering key), sent as a string. */
public record CommentDto(UUID commentId, String content, OffsetDateTime createdAt, String authorName) {
}
