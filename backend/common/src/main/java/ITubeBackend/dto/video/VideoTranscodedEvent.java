package ITubeBackend.dto.video;

import java.util.List;

/**
 * transcode-worker -> catalog-service (topic video.transcoded, key = video id): the renditions now in object
 * storage, and whether the video is READY or FAILED. Handling it twice is harmless (renditions are upserts).
 */
public record VideoTranscodedEvent(int schemaVersion, String eventType, Long videoId, String status,
                                   List<Rendition> renditions) {
    public static final String TYPE = "VideoTranscoded";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";

    public VideoTranscodedEvent(Long videoId, String status, List<Rendition> renditions) {
        this(1, TYPE, videoId, status, renditions);
    }

    public record Rendition(String quality, String storageKey, long sizeBytes) {
    }
}
