package ITubeBackend.dto.video;

public record VideoTranscodeMessage(int schemaVersion, String eventType, Long videoId) {
}
