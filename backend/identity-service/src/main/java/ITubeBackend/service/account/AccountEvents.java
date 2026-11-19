package ITubeBackend.service.account;

import ITubeBackend.common.events.Events;
import ITubeBackend.common.events.ItubeTopics;
import ITubeBackend.dto.accounts.AccountCreatedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Publishes account.created (key = user id), consumed by catalog-service to create the first channel. */
@Component
public class AccountEvents {
    private static final Logger log = LoggerFactory.getLogger(AccountEvents.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ItubeTopics topics;

    public AccountEvents(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, ItubeTopics topics) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topics = topics;
    }

    public void accountCreated(Long userId, String channelName) {
        String payload = Events.toJson(objectMapper, new AccountCreatedEvent(userId, channelName));
        try {
            kafkaTemplate.send(topics.accountCreated(), userId.toString(), payload).whenComplete((result, error) -> {
                if (error != null) log.warn("Could not publish account.created for user {}: {}", userId, error.toString());
            });
        } catch (RuntimeException exception) {
            // Registration already succeeded; the channel was (or will be) created by the direct catalog call
            log.warn("Could not publish account.created for user {}: {}", userId, exception.toString());
        }
    }

    @Configuration
    static class Topics {
        @Bean
        NewTopic accountCreatedTopic(ItubeTopics topics) {
            return topics.declare(topics.accountCreated());
        }
    }
}
