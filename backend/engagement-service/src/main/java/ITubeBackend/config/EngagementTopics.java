package ITubeBackend.config;

import ITubeBackend.common.events.ItubeTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Dead-letter topic for the video.deleted messages this service couldn't process (see KafkaResilienceConfig). */
@Configuration
public class EngagementTopics {
    @Bean
    NewTopic videoDeletedDeadLetterTopic(ItubeTopics topics) {
        return topics.declare(ItubeTopics.deadLetter(topics.videoDeleted()));
    }
}
