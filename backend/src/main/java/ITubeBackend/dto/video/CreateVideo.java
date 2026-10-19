package ITubeBackend.dto.video;

import java.time.LocalTime;

public record CreateVideo(Long channelId, byte[] video, String videoContentType, byte[] thumbnail, String thumbnailContentType, String title, LocalTime duration) {
}
