package ITubeBackend.engagement.commands;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "itube.engagement.kafka.transport", havingValue = "kafka", matchIfMissing = true)
public class KafkaCommandTransport implements CommandTransport {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topic;
    public KafkaCommandTransport(KafkaTemplate<String, String> kafka, ObjectMapper json,
            @Value("${itube.engagement.kafka.topic}") String topic) { this.kafka = kafka; this.json = json; this.topic = topic; }
    public void publish(EngagementCommand command) {
        try {
            // Wait for the broker acknowledgement, not for consumer processing. Never acknowledge a fire-and-forget send.
            kafka.send(topic, Long.toString(command.userId()), json.writeValueAsString(command)).get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Kafka acknowledgement unavailable. The outcome may be unknown; retry using the same Idempotency-Key.", exception);
        }
    }
}
