package ITubeBackend.common.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.kafka.config.TopicBuilder;

/** itube.topics.* - Kafka topic names shared by producers and consumers in different services. */
@ConfigurationProperties(prefix = "itube.topics")
public record ItubeTopics(String videoTranscode, String videoTranscoded, String videoDeleted, String accountCreated,
                          String catalogCacheInvalidation, String membershipActivated, String membershipExpired,
                          String membershipTierChanged, int partitions, int replicas) {

    public ItubeTopics {
        if (partitions < 1) partitions = 6;
        if (replicas < 1) replicas = 1;
    }

    /** Declares a topic (created at startup if missing) with the configured partitions and replicas. */
    public NewTopic declare(String name) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
    }

    /** Where messages that still fail after every retry are parked: {@code <topic>-dlt}. */
    public static String deadLetter(String topic) {
        return topic + "-dlt";
    }
}
