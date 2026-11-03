package ITubeBackend.dto.video;

import ITubeBackend.entities.VideoVisibility;

/** The fields needed to decide who may play a video. */
public record VideoAccessDto(Long videoId, Long channelId, Long ownerUserId, VideoVisibility visibility,
                             Integer requiredTierLevel) {
}
