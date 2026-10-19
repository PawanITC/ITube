package ITubeBackend.dto.video;

import java.time.LocalTime;

public record VideoSummaryDto(
        Long videoId,
        Long channelId,
        String title,
        String channelName,
        LocalTime duration,
        String videoUrl,
        String thumbnailUrl) {
    public VideoSummaryDto(Long videoId, Long channelId, String title, String channelName, LocalTime duration, boolean hasThumbnail) {
        this(videoId, channelId, title, channelName, duration,
                "/api/videos/" + videoId + "/file",
                hasThumbnail ? "/api/videos/" + videoId + "/thumbnail" : null);
    }
}
