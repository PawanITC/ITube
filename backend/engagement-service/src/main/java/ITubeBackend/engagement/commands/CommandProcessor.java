package ITubeBackend.engagement.commands;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;

@Component
public class CommandProcessor {
    private final OperationStore operations;
    private final MutationPlanner planner;
    private final ProjectionWriter writer;
    private final Clock clock;
    private final MeterRegistry metrics;
    public CommandProcessor(OperationStore operations, MutationPlanner planner, ProjectionWriter writer,
            Clock clock, MeterRegistry metrics) {
        this.operations = operations; this.planner = planner; this.writer = writer; this.clock = clock; this.metrics = metrics;
    }
    public void process(EngagementCommand incoming) {
        var operation = operations.register(incoming);
        EngagementCommand command = operation.command();
        if (!command.sameIntent(incoming)) throw new InvalidCommandEnvelopeException("Idempotency key reused with different intent");
        if (!operation.state().equals("PENDING")) return;
        try {
            MutationPlan plan = operation.plan();
            if (plan == null) {
                long timestamp = operations.nextWriteMicros(command.userId(), clock.millis() * 1000);
                plan = operations.prepare(command, planner.prepare(command, timestamp));
            }
            writer.apply(command, plan);
            operations.finish(command, "SUCCEEDED", 200, null);
            metrics.counter("itube.engagement.commands", "action", command.action().name(), "outcome", "succeeded").increment();
        } catch (ResponseStatusException exception) {
            // Rejected intent is terminal; infrastructure failures are rethrown so the partition cannot overtake them.
            if (!exception.getStatusCode().is4xxClientError()) throw exception;
            operations.finish(command, "FAILED", exception.getStatusCode().value(), exception.getReason());
            metrics.counter("itube.engagement.commands", "action", command.action().name(), "outcome", "rejected").increment();
        }
    }
}
