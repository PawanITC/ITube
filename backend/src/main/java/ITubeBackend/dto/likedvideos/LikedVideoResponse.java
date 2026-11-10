package ITubeBackend.dto.likedvideos;

import ITubeBackend.dto.video.VideoSummaryDto;

import java.time.Instant;

public record LikedVideoResponse(VideoSummaryDto video, Instant likedAt) {
}
