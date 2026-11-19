package ITubeBackend.messaging;

import ITubeBackend.common.events.Events;
import ITubeBackend.dto.accounts.AccountCreatedEvent;
import ITubeBackend.service.channel.ChannelService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates a new account's first channel (event from identity-service). identity-service publishes it only when it
 * could not create the channel directly (catalog was unavailable), and ensureChannel creates at most one channel
 * per (user, name), so the account always ends up with exactly one first channel.
 */
@Component
public class AccountCreatedListener {
    private final ChannelService channelService;
    private final ObjectMapper objectMapper;

    public AccountCreatedListener(ChannelService channelService, ObjectMapper objectMapper) {
        this.channelService = channelService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${itube.topics.account-created}", groupId = "catalog-account-created")
    public void onAccountCreated(String payload) {
        AccountCreatedEvent event = Events.accountCreated(objectMapper, payload);
        channelService.ensureChannel(event.userId(), event.channelName());
    }
}
