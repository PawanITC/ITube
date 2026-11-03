package ITubeBackend.service.payments;

import ITubeBackend.entities.OutboxEventEntity;
import ITubeBackend.repository.OutboxEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Records a Kafka message in the caller's transaction. If the transaction rolls back, the message
 * is never sent; if it commits, {@link OutboxPublisher} sends it.
 */
@Component
public class OutboxWriter {
    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String topic, String key, UUID eventId, Object payload) {
        repository.save(new OutboxEventEntity(eventId, topic, key, objectMapper.writeValueAsString(payload),
                OffsetDateTime.now(clock)));
    }
}
