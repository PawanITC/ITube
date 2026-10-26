package ITubeBackend.dto.video;

import java.time.LocalTime;

public record VideoSummaryDto(
        Long videoId,
        Long channelId,
        String title,
        String channelName,
        LocalTime duration,
        String processingStatus,
        String videoUrl,
        String thumbnailUrl) {
    public VideoSummaryDto(Long videoId, Long channelId, String title, String channelName, LocalTime duration, boolean hasThumbnail, String processingStatus) {
        this(videoId, channelId, title, channelName, duration, processingStatus,
                "/api/videos/" + videoId + "/file",
                hasThumbnail ? "/api/videos/" + videoId + "/thumbnail" : null);
    }
}
