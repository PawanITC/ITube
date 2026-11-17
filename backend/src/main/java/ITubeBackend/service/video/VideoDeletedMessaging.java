package ITubeBackend.service.video;

import ITubeBackend.dto.video.VideoDeletedEvent;
import ITubeBackend.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends video.deleted once the delete commits, and removes the video's stored files when it arrives.
 * Other domains (engagement) subscribe to the same topic with their own consumer group.
 * A lost message only leaves orphaned files/rows behind: every reader already ignores ids whose
 * video no longer exists, so cleanup is garbage collection, not correctness.
 */
@Component
public class VideoDeletedMessaging {
    private static final Logger log = LoggerFactory.getLogger(VideoDeletedMessaging.class);
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectStorage storage;
    private final String topic;

    public VideoDeletedMessaging(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                 ObjectStorage storage, @Value("${itube.video.deleted-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.storage = storage;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(VideoDeletedEvent event) {
        kafkaTemplate.send(topic, event.videoId().toString(), objectMapper.writeValueAsString(event))
                .whenComplete((result, error) -> {
                    if (error != null) log.warn("Could not publish video.deleted for video {}: {}", event.videoId(), error.toString());
                });
    }

    @KafkaListener(topics = "${itube.video.deleted-topic}", groupId = "itube-media-cleanup")
    public void removeStoredFiles(String payload) {
        VideoDeletedEvent event = parse(objectMapper, payload);
        for (String key : event.storageKeys()) {
            storage.delete(key);
        }
    }

    public static VideoDeletedEvent parse(ObjectMapper objectMapper, String payload) {
        VideoDeletedEvent event = objectMapper.readValue(payload, VideoDeletedEvent.class);
        if (!VideoDeletedEvent.TYPE.equals(event.eventType()) || event.schemaVersion() != 1) {
            throw new IllegalArgumentException("Unsupported video.deleted message: " + payload);
        }
        return event;
    }
}
