package ITubeBackend.dto.video;

import java.util.List;

/**
 * Published (as a Spring event, then to Kafka after commit) when a video row is deleted.
 * storageKeys lists every stored file so consumers don't need the deleted row.
 */
public record VideoDeletedEvent(int schemaVersion, String eventType, Long videoId, List<String> storageKeys) {
    public static final String TYPE = "VideoDeleted";

    public VideoDeletedEvent(Long videoId, List<String> storageKeys) {
        this(1, TYPE, videoId, storageKeys);
    }
}
