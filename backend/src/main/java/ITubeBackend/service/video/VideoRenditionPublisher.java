package ITubeBackend.service.video;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

// Publishes renditions to Kafka; the video-rendition-sink connector (tf_docker/kafka.tf) writes them into video_rendition

@Component
public class VideoRenditionPublisher {
    // Kafka Connect JsonConverter needs the schema alongside each record so the JDBC sink can map fields to columns
    private static final String SCHEMA = "{\"type\":\"struct\",\"name\":\"video_rendition\",\"optional\":false,\"fields\":["
            + "{\"field\":\"video_id\",\"type\":\"int64\",\"optional\":false},"
            + "{\"field\":\"quality\",\"type\":\"string\",\"optional\":false},"
            + "{\"field\":\"content_type\",\"type\":\"string\",\"optional\":false},"
            + "{\"field\":\"content\",\"type\":\"bytes\",\"optional\":false}]}";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public VideoRenditionPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                   @Value("${itube.video.rendition-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(Long videoId, String quality, byte[] bytes) throws Exception {
        String record = "{\"schema\":" + SCHEMA + ",\"payload\":{"
                + "\"video_id\":" + videoId + ","
                + "\"quality\":\"" + quality + "\","
                + "\"content_type\":\"video/mp4\","
                + "\"content\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"}}";
        // Block until the broker acknowledges so a failed send marks the video FAILED
        kafkaTemplate.send(topic, videoId + "-" + quality, record).get(5, TimeUnit.MINUTES);
    }
}
