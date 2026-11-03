package ITubeBackend.dto.video;

import ITubeBackend.entities.VideoVisibility;

import java.time.LocalTime;

public record CreateVideo(Long channelId, byte[] video, String videoContentType, byte[] thumbnail, String thumbnailContentType, String title, LocalTime duration,
                          VideoVisibility visibility, Integer requiredTierLevel) {
}
