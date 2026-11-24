package ITubeBackend.config;

import ITubeBackend.common.events.ItubeTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Dead-letter topic for the video.deleted messages this service couldn't process (see KafkaResilienceConfig). */
@Configuration
public class EngagementTopics {
    @Bean
    NewTopic engagementCommandsTopic(ItubeTopics topics,
            @org.springframework.beans.factory.annotation.Value("${itube.engagement.kafka.topic}") String name) {
        return org.springframework.kafka.config.TopicBuilder.name(name).partitions(topics.partitions())
                .replicas(topics.replicas()).config("retention.ms", "604800000")
                .config("segment.bytes", "16777216").config("segment.ms", "3600000")
                .config("min.insync.replicas", Integer.toString(Math.min(2, topics.replicas())))
                .config("retention.bytes", "67108864").build();
    }

    @Bean
    NewTopic engagementCommandsDeadLetterTopic(ItubeTopics topics,
            @org.springframework.beans.factory.annotation.Value("${itube.engagement.kafka.topic}") String name) {
        return org.springframework.kafka.config.TopicBuilder.name(ItubeTopics.deadLetter(name)).partitions(topics.partitions())
                .replicas(topics.replicas()).config("retention.ms", "1209600000")
                .config("segment.bytes", "16777216").config("segment.ms", "3600000")
                .config("min.insync.replicas", Integer.toString(Math.min(2, topics.replicas())))
                .config("retention.bytes", "67108864").build();
    }

    @Bean
    NewTopic videoDeletedDeadLetterTopic(ItubeTopics topics) {
        return topics.declare(ItubeTopics.deadLetter(topics.videoDeleted()));
    }
}
