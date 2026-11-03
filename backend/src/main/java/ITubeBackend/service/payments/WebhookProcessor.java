package ITubeBackend.service.payments;

import ITubeBackend.config.StripeProperties;
import ITubeBackend.repository.WebhookInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/** Drains the webhook inbox. Each event runs in its own transaction and failures back off and retry. */
@Component
public class WebhookProcessor {
    private static final Logger log = LoggerFactory.getLogger(WebhookProcessor.class);
    private static final int BATCH_SIZE = 25;

    private final WebhookInboxRepository inboxRepository;
    private final WebhookEventHandler handler;
    private final StripeProperties properties;
    private final Clock clock;

    public WebhookProcessor(WebhookInboxRepository inboxRepository, WebhookEventHandler handler,
                            StripeProperties properties, Clock clock) {
        this.inboxRepository = inboxRepository;
        this.handler = handler;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${itube.stripe.webhook-poll-interval:PT1S}")
    public void processReady() {
        if (!properties.configured()) return;
        for (Long id : inboxRepository.findReadyIds(OffsetDateTime.now(clock), BATCH_SIZE)) {
            try {
                handler.process(id);
            } catch (Exception exception) {
                log.warn("Processing webhook inbox row {} failed, will retry: {}", id, exception.toString());
                handler.recordFailure(id, exception);
            }
        }
    }

    /** Stored payloads can contain customer data, so processed events are not kept forever. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeProcessed() {
        int deleted = inboxRepository.deleteProcessedBefore(OffsetDateTime.now(clock).minus(properties.webhookRetention()));
        if (deleted > 0) log.info("Purged {} processed Stripe events", deleted);
    }
}
