package ITubeBackend.config;

import ITubeBackend.common.events.ItubeTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Topics payments-service publishes (through its transactional outbox, see OutboxPublisher). */
@Configuration
public class PaymentTopics {
    @Bean
    NewTopic membershipActivatedTopic(ItubeTopics topics) {
        return topics.declare(topics.membershipActivated());
    }

    @Bean
    NewTopic membershipExpiredTopic(ItubeTopics topics) {
        return topics.declare(topics.membershipExpired());
    }

    @Bean
    NewTopic membershipTierChangedTopic(ItubeTopics topics) {
        return topics.declare(topics.membershipTierChanged());
    }
}
