package ITubeBackend.common.kafka;

import ITubeBackend.common.events.ItubeTopics;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.time.Duration;

/**
 * What happens when a Kafka listener throws, for every service (Spring Boot plugs this handler into all
 * {@code @KafkaListener}s):
 * <ol>
 *   <li>Transient failures (database blip, downstream restarting) are retried in place with exponential backoff:
 *       500 ms, 1 s, 2 s ... up to {@code itube.kafka.retry.max-attempts}.</li>
 *   <li>If it still fails, the message is published to {@code <topic>-dlt} (dead-letter topic) and the consumer
 *       moves on. One poison message never blocks the partition behind it.</li>
 *   <li>Malformed messages (IllegalArgumentException) skip the retries and go straight to the DLT.</li>
 * </ol>
 * Fix the cause, then replay the DLT into the main topic. Every consumer is idempotent, so replays are safe.
 */
@Configuration
public class KafkaResilienceConfig {
    private static final Logger log = LoggerFactory.getLogger(KafkaResilienceConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                          @Value("${itube.kafka.retry.max-attempts:5}") int maxAttempts,
                                          @Value("${itube.kafka.retry.initial-interval:500ms}") Duration initialInterval,
                                          @Value("${itube.kafka.retry.max-interval:10s}") Duration maxInterval) {
        // Partition -1: let Kafka choose, so the DLT doesn't need as many partitions as the source topic
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> {
                    log.error("Giving up on {}-{}@{} after retries, sending to {}: {}", record.topic(),
                            record.partition(), record.offset(), ItubeTopics.deadLetter(record.topic()),
                            exception.toString());
                    return new TopicPartition(ItubeTopics.deadLetter(record.topic()), -1);
                });
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(Math.max(0, maxAttempts - 1));
        backOff.setInitialInterval(initialInterval.toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(maxInterval.toMillis());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
