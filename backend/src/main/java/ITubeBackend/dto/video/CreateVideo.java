package ITubeBackend.dto.video;

import ITubeBackend.entities.VideoVisibility;
import java.time.LocalTime;

/** thumbnail may be null. */
public record CreateVideo(Long channelId, MediaUpload video, MediaUpload thumbnail, String title, LocalTime duration,
                          VideoVisibility visibility, Integer requiredTierLevel) {

}
