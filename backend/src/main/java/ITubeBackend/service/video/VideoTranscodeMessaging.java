package ITubeBackend.service.video;

import ITubeBackend.dto.video.VideoTranscodeMessage;
import ITubeBackend.dto.video.VideoUploadedEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// REVIEW

@Component
public class VideoTranscodeMessaging {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final VideoTranscodeWorker worker;
    private final ObjectMapper objectMapper;
    private final String topic;

    public VideoTranscodeMessaging(KafkaTemplate<String, String> kafkaTemplate, VideoTranscodeWorker worker,
                                   ObjectMapper objectMapper,
                                   @Value("${itube.video.transcode-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.worker = worker;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enqueue(VideoUploadedEvent event) {
        VideoTranscodeMessage message = new VideoTranscodeMessage(1, "VideoUploaded", event.videoId());
        try {
            kafkaTemplate.send(topic, event.videoId().toString(), objectMapper.writeValueAsString(message));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize video transcode message", exception);
        }
    }

    @KafkaListener(topics = "${itube.video.transcode-topic}")
    public void transcode(String payload) {
        Long videoId;
        if (payload.trim().startsWith("{")) {
            try {
                VideoTranscodeMessage message = objectMapper.readValue(payload, VideoTranscodeMessage.class);
                if (!"VideoUploaded".equals(message.eventType()) || message.schemaVersion() != 1) {
                    throw new IllegalArgumentException("Unsupported video transcode message: " + payload);
                }
                videoId = message.videoId();
            } catch (JacksonException exception) {
                throw new IllegalArgumentException("Invalid video transcode message: " + payload, exception);
            }
        } else {
            // Accept messages produced before the payload was changed to JSON.
            videoId = Long.parseLong(payload);
        }
        worker.transcode(videoId);
    }
}
