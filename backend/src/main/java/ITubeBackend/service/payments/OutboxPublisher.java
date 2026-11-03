package ITubeBackend.service.payments;

import ITubeBackend.entities.OutboxEventEntity;
import ITubeBackend.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

/** Sends committed outbox rows to Kafka (at least once). Safe to run on several instances. */
@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 10;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                           TransactionTemplate transactionTemplate, Clock clock, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.published = meterRegistry.counter("itube.outbox.published");
        this.failed = meterRegistry.counter("itube.outbox.failed");
    }

    @Scheduled(fixedDelayString = "${itube.outbox.poll-interval:PT1S}")
    public void publishReady() {
        transactionTemplate.executeWithoutResult(status -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            for (OutboxEventEntity event : repository.lockReady(now, BATCH_SIZE)) {
                publish(event, now);
            }
        });
    }

    private void publish(OutboxEventEntity event, OffsetDateTime now) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()).get(10, TimeUnit.SECONDS);
            event.markPublished(now);
            published.increment();
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            Duration backoff = Duration.ofSeconds(Math.min(600, 5L << Math.min(event.getAttempts(), 7)));
            event.markFailedAttempt(exception.toString(), now.plus(backoff), MAX_ATTEMPTS);
            if ("FAILED".equals(event.getStatus())) {
                failed.increment();
                log.error("Outbox event {} to {} failed permanently after {} attempts",
                        event.getEventId(), event.getTopic(), event.getAttempts());
            } else {
                log.warn("Outbox event {} to {} failed, will retry: {}", event.getEventId(), event.getTopic(),
                        exception.toString());
            }
        }
    }
}
