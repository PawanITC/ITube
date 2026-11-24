package ITubeBackend.dto.video;

import java.time.OffsetDateTime;

/** What the watch page shows under the player: the description (null if none), view count and upload time. */
public record VideoDetailsDto(Long videoId, String description, long viewCount, OffsetDateTime createdAt) {
}
