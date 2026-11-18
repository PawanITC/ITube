package ITubeBackend.dto.video;

/** In-process event: a video row was inserted. Sent to Kafka (video.transcode) only after the transaction commits. */
public record VideoUploadedEvent(Long videoId, String videoKey) {
}
