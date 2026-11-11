package ITubeBackend.dto.likedvideos;

import ITubeBackend.entities.VideoVisibility;

import java.time.Instant;
import java.time.LocalTime;

/** Database projection: metadata only, without loading video or thumbnail bytes. */
public record LikedVideoRow(Long videoId, Long channelId, String title, String channelName,
                            LocalTime duration, boolean hasThumbnail, String processingStatus,
                            VideoVisibility visibility, Integer requiredTierLevel, Instant likedAt) {
}
