package ITubeBackend.dto.comments;

import java.time.OffsetDateTime;

public record CommentDto(Long commentId, String content, OffsetDateTime createdAt, String authorName) {
}
