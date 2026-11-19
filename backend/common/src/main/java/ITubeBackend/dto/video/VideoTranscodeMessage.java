package ITubeBackend.dto.video;

/**
 * catalog-service -> transcode-worker (topic video.transcode, key = video id).
 * Version 2 carries the source key, so the worker never needs the catalog database.
 * Version 1 (no videoKey) is still accepted: the worker then asks the catalog for the key.
 */
public record VideoTranscodeMessage(int schemaVersion, String eventType, Long videoId, String videoKey) {
    public static final String TYPE = "VideoUploaded";

    public VideoTranscodeMessage(Long videoId, String videoKey) {
        this(2, TYPE, videoId, videoKey);
    }
}
