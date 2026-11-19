package ITubeBackend.dto.video;

import ITubeBackend.entities.VideoVisibility;

import java.time.LocalTime;

/**
 * videoUrl is only set for videos anyone can play. For MEMBERS_ONLY and PRIVATE videos the client
 * must call /api/videos/{id}/playback-authorisation to get a token first.
 */
public record VideoSummaryDto(
        Long videoId,
        Long channelId,
        String title,
        String channelName,
        LocalTime duration,
        String processingStatus,
        String visibility,
        Integer requiredTierLevel,
        String videoUrl,
        String thumbnailUrl) {
    public VideoSummaryDto(Long videoId, Long channelId, String title, String channelName, LocalTime duration,
                           boolean hasThumbnail, String processingStatus, VideoVisibility visibility,
                           Integer requiredTierLevel) {
        this(videoId, channelId, title, channelName, duration, processingStatus, visibility.name(), requiredTierLevel,
                visibility.openToEveryone() ? "/api/videos/" + videoId + "/file" : null,
                hasThumbnail ? "/api/videos/" + videoId + "/thumbnail" : null);
    }
}
