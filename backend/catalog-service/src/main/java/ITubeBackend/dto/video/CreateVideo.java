package ITubeBackend.dto.video;

import ITubeBackend.entities.VideoType;
import ITubeBackend.entities.VideoVisibility;
import java.time.LocalTime;

/**
 * thumbnail may be null. videoType null means VIDEO; adsEnabled and adCount only apply to VIDEO.
 * adCount null means one ad.
 */
public record CreateVideo(Long channelId, MediaUpload video, MediaUpload thumbnail, String title, LocalTime duration,
                          VideoVisibility visibility, Integer requiredTierLevel, VideoType videoType,
                          boolean adsEnabled, Integer adCount) {

    /** A normal video without ads. */
    public CreateVideo(Long channelId, MediaUpload video, MediaUpload thumbnail, String title, LocalTime duration,
                       VideoVisibility visibility, Integer requiredTierLevel) {
        this(channelId, video, thumbnail, title, duration, visibility, requiredTierLevel, VideoType.VIDEO, false, null);
    }
}
