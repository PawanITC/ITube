package ITubeBackend.dto.video;

/**
 * One transcoded quality of a video and its total size, so the player can work out its bitrate. format is "hls"
 * (segments + playlist, played through /api/videos/{id}/hls/master.m3u8) or "mp4" (a single file from before HLS,
 * played through /api/videos/{id}/renditions/{quality}). sizeBytes may be null.
 */
public record QualityLevelDto(String quality, Long sizeBytes, String format) {
    public static final String HLS = "hls";
    public static final String MP4 = "mp4";

    public boolean isHls() {
        return HLS.equals(format);
    }
}
