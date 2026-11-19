package ITubeBackend.service.video;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.events.Events;
import ITubeBackend.common.events.ItubeTopics;
import ITubeBackend.dto.video.VideoTranscodeMessage;
import ITubeBackend.dto.video.VideoTranscodedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Consumes video.transcode, produces video.transcoded.
 * <ul>
 *   <li>Transient failures (storage hiccup, worker killed mid-way) throw: Kafka redelivers with backoff, and after the
 *       last attempt the message goes to video.transcode-dlt (KafkaResilienceConfig).</li>
 *   <li>A source FFmpeg can't read will never work: it is reported as FAILED straight away instead of retried.</li>
 * </ul>
 * One message at a time per consumer (FFmpeg uses the whole CPU); scale by adding worker pods, up to the number
 * of partitions of video.transcode.
 */
@Component
public class TranscodeListener {
    private static final Logger log = LoggerFactory.getLogger(TranscodeListener.class);

    private final FfmpegTranscoder transcoder;
    private final CatalogClient catalog;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ItubeTopics topics;

    public TranscodeListener(FfmpegTranscoder transcoder, CatalogClient catalog, KafkaTemplate<String, String> kafkaTemplate,
                             ObjectMapper objectMapper, ItubeTopics topics) {
        this.transcoder = transcoder;
        this.catalog = catalog;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topics = topics;
    }

    // Same consumer group as the old monolith, so no message queued before the split is processed twice or skipped
    @KafkaListener(topics = "${itube.topics.video-transcode}", groupId = "itube-video-transcoders")
    public void onTranscode(String payload) throws Exception {
        VideoTranscodeMessage message = Events.videoTranscode(objectMapper, payload);
        String sourceKey = message.videoKey() != null ? message.videoKey()
                : catalog.sourceKey(message.videoId()).orElse(null);
        if (sourceKey == null) {
            log.warn("Video {} no longer exists; nothing to transcode", message.videoId());
            return;
        }
        VideoTranscodedEvent result;
        try {
            result = new VideoTranscodedEvent(message.videoId(), VideoTranscodedEvent.READY,
                    transcoder.transcode(message.videoId(), sourceKey));
        } catch (FfmpegTranscoder.UnprocessableVideoException unprocessable) {
            log.warn("Video {} can't be transcoded: {}", message.videoId(), unprocessable.getMessage());
            result = new VideoTranscodedEvent(message.videoId(), VideoTranscodedEvent.FAILED, List.of());
        }
        // Wait for Kafka to confirm: if the result can't be sent, fail so this message is redelivered
        try {
            kafkaTemplate.send(topics.videoTranscoded(), message.videoId().toString(), Events.toJson(objectMapper, result))
                    .get(30, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Could not publish the transcode result of video " + message.videoId(),
                    exception.getCause());
        }
        log.info("Video {} transcoded: {}", message.videoId(), result.status());
    }

    @Configuration
    static class Topics {
        @Bean
        NewTopic videoTranscodeDeadLetterTopic(ItubeTopics topics) {
            return topics.declare(ItubeTopics.deadLetter(topics.videoTranscode()));
        }
    }
}
