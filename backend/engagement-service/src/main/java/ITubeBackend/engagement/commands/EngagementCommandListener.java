package ITubeBackend.engagement.commands;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class EngagementCommandListener {
    private final ObjectMapper json;
    private final CommandProcessor processor;
    public EngagementCommandListener(ObjectMapper json, CommandProcessor processor) { this.json = json; this.processor = processor; }

    @KafkaListener(topics = "${itube.engagement.kafka.topic}", groupId = "itube-engagement-commands-v1",
            containerFactory = "engagementCommandFactory", autoStartup = "${spring.kafka.listener.auto-startup:true}",
            concurrency = "${itube.engagement.kafka.concurrency:3}", properties = {"enable.auto.commit=false", "max.poll.records=10"})
    public void consume(ConsumerRecord<String, String> record) {
        EngagementCommand command;
        try { command = json.readValue(record.value(), EngagementCommand.class); }
        catch (RuntimeException exception) { throw new InvalidCommandEnvelopeException("Malformed engagement message", exception); }
        if (command == null || command.schemaVersion() != 1 || command.userId() <= 0 || command.action() == null
                || command.operationId() == null || command.operationId().version() != 4 || command.occurredAt() == null
                || !Long.toString(command.userId()).equals(record.key())) {
            throw new InvalidCommandEnvelopeException("Invalid engagement envelope or partition key");
        }
        processor.process(command);
    }
}
