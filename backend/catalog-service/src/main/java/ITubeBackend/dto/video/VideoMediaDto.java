package ITubeBackend.dto.video;

/** Object storage keys for a video; no bytes. */
public record VideoMediaDto(String videoKey, String videoContentType, String thumbnailKey, String thumbnailContentType) {
}
