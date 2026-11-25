package ITubeBackend.config;

import ITubeBackend.engagement.*;
import ITubeBackend.engagement.commands.*;
import ITubeBackend.engagement.scylla.ScyllaProperties;
import ITubeBackend.service.playlists.PlaylistService;
import ITubeBackend.service.playlists.AutomaticPlaylistService;
import com.datastax.oss.driver.api.core.CqlSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.common.TopicPartition;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class EngagementCommandConfig {
    @Bean
    @Profile("h2")
    @ConditionalOnProperty(name = "itube.engagement.kafka.transport", havingValue = "inline")
    CommandTransport inlineTestTransport(CommandProcessor processor) { return processor::process; }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> engagementCommandFactory(
            ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> kafka,
            io.micrometer.core.instrument.MeterRegistry metrics) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);
        var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, error) -> new TopicPartition(record.topic() + "-dlt", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        // Do not skip an infrastructure failure: a later unlike/clear must never overtake an incomplete write.
        // Only malformed envelopes are parked in the DLT. Fix outages and the partition resumes in order.
        var errors = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        errors.setClassifications(java.util.Map.of(InvalidCommandEnvelopeException.class, false), true);
        errors.setRetryListeners(new RetryListener() {
            @Override public void failedDelivery(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                    Exception error, int attempt) {
                metrics.counter("itube.engagement.command.failures").increment();
            }
            @Override public void recovered(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Exception error) {
                metrics.counter("itube.engagement.command.deadletters").increment();
            }
        });
        factory.setCommonErrorHandler(errors);
        return factory;
    }

    @Configuration
    @ConditionalOnProperty(name = "itube.engagement.store", havingValue = "scylla", matchIfMissing = true)
    static class Scylla {
        @Bean OperationStore operationStore(CqlSession session, ScyllaProperties properties, ObjectMapper json) {
            return new ScyllaOperationStore(session, properties.keyspace(), json);
        }
        @Bean ProjectionWriter projectionWriter(CqlSession session, ScyllaProperties properties, PlaylistService playlists,
                AutomaticPlaylistService automatic) {
            return new ScyllaProjectionWriter(session, properties.keyspace(), playlists, automatic);
        }
    }
    @Configuration
    @ConditionalOnProperty(name = "itube.engagement.store", havingValue = "memory")
    static class Memory {
        @Bean OperationStore operationStore() { return new MemoryOperationStore(); }
        @Bean ProjectionWriter projectionWriter(WatchHistoryStore history, LikeStore likes, SavedVideoStore saved, PlaylistService playlists,
                AutomaticPlaylistService automatic) {
            return new MemoryProjectionWriter(history, likes, saved, playlists, automatic);
        }
    }
}
