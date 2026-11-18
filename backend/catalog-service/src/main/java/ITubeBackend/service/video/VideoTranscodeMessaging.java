package ITubeBackend.service.video;

import ITubeBackend.common.events.Events;
import ITubeBackend.common.events.ItubeTopics;
import ITubeBackend.dto.video.VideoTranscodeMessage;
import ITubeBackend.dto.video.VideoUploadedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * The catalog's side of the transcode pipeline (the worker is a separate service, transcode-worker):
 * <pre>
 *   catalog --video.transcode--> transcode-worker --video.transcoded--> catalog
 * </pre>
 * Uploading never waits for transcoding: the video is saved as PROCESSING and the creator gets an answer at once.
 * If a message is lost anyway (e.g. Kafka was down at that moment), {@link TranscodeSweeper} sends it again.
 */
@Component
public class VideoTranscodeMessaging {
    private static final Logger log = LoggerFactory.getLogger(VideoTranscodeMessaging.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final VideoService videoService;
    private final ObjectMapper objectMapper;
    private final ItubeTopics topics;

    public VideoTranscodeMessaging(KafkaTemplate<String, String> kafkaTemplate, VideoService videoService,
                                   ObjectMapper objectMapper, ItubeTopics topics) {
        this.kafkaTemplate = kafkaTemplate;
        this.videoService = videoService;
        this.objectMapper = objectMapper;
        this.topics = topics;
    }

    /** After the video row commits (never for an insert that rolled back). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enqueue(VideoUploadedEvent event) {
        send(event.videoId(), event.videoKey());
    }

    /** Key = video id: all messages for one video go to the same partition, in order. */
    public void send(Long videoId, String videoKey) {
        String payload = Events.toJson(objectMapper, new VideoTranscodeMessage(videoId, videoKey));
        try {
            kafkaTemplate.send(topics.videoTranscode(), videoId.toString(), payload).whenComplete((result, error) -> {
                if (error != null) log.warn("Could not queue video {} for transcoding (the sweeper will retry): {}",
                        videoId, error.toString());
            });
        } catch (RuntimeException exception) {
            log.warn("Could not queue video {} for transcoding (the sweeper will retry): {}", videoId, exception.toString());
        }
    }

    @KafkaListener(topics = "${itube.topics.video-transcoded}", groupId = "catalog-transcode-results")
    public void onTranscoded(String payload) {
        videoService.applyTranscodeResult(Events.videoTranscoded(objectMapper, payload));
    }

    @Configuration
    static class Topics {
        @Bean
        NewTopic videoTranscodeTopic(ItubeTopics topics) {
            return topics.declare(topics.videoTranscode());
        }

        @Bean
        NewTopic videoTranscodedTopic(ItubeTopics topics) {
            return topics.declare(topics.videoTranscoded());
        }

        @Bean
        NewTopic videoTranscodedDeadLetterTopic(ItubeTopics topics) {
            return topics.declare(ItubeTopics.deadLetter(topics.videoTranscoded()));
        }

        @Bean
        NewTopic videoDeletedTopic(ItubeTopics topics) {
            return topics.declare(topics.videoDeleted());
        }

        @Bean
        NewTopic videoDeletedDeadLetterTopic(ItubeTopics topics) {
            return topics.declare(ItubeTopics.deadLetter(topics.videoDeleted()));
        }

        @Bean
        NewTopic accountCreatedDeadLetterTopic(ItubeTopics topics) {
            return topics.declare(ItubeTopics.deadLetter(topics.accountCreated()));
        }
    }
}
