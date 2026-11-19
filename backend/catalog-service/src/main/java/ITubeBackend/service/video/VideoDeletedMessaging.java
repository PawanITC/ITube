package ITubeBackend.service.video;

import ITubeBackend.common.events.Events;
import ITubeBackend.common.events.ItubeTopics;
import ITubeBackend.dto.video.VideoDeletedEvent;
import ITubeBackend.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends video.deleted once the delete commits, and removes the video's stored files when it arrives.
 * Other services (engagement-service) subscribe to the same topic with their own consumer group.
 * A lost message only leaves orphaned files/rows behind: every reader already ignores ids whose
 * video no longer exists, so cleanup is garbage collection, not correctness.
 */
@Component
public class VideoDeletedMessaging {
    private static final Logger log = LoggerFactory.getLogger(VideoDeletedMessaging.class);
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectStorage storage;
    private final ItubeTopics topics;

    public VideoDeletedMessaging(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                 ObjectStorage storage, ItubeTopics topics) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.storage = storage;
        this.topics = topics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(VideoDeletedEvent event) {
        try {
            kafkaTemplate.send(topics.videoDeleted(), event.videoId().toString(), Events.toJson(objectMapper, event))
                    .whenComplete((result, error) -> {
                        if (error != null) log.warn("Could not publish video.deleted for video {}: {}", event.videoId(), error.toString());
                    });
        } catch (RuntimeException exception) {
            log.warn("Could not publish video.deleted for video {}: {}", event.videoId(), exception.toString());
        }
    }

    /** Idempotent: deleting a key that's already gone is fine, so redeliveries and DLT replays are harmless. */
    @KafkaListener(topics = "${itube.topics.video-deleted}", groupId = "itube-media-cleanup")
    public void removeStoredFiles(String payload) {
        VideoDeletedEvent event = Events.videoDeleted(objectMapper, payload);
        for (String key : event.storageKeys()) {
            storage.delete(key);
        }
    }
}
