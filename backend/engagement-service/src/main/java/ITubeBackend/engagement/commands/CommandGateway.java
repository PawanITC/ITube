package ITubeBackend.engagement.commands;

import ITubeBackend.dto.engagement.OperationResponse;
import ITubeBackend.engagement.TimeUuids;
import org.springframework.stereotype.Service;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.net.URI;
import java.util.UUID;

@Service
public class CommandGateway {
    private final CommandTransport transport;
    private final MutationPlanner planner;
    private final OperationStore operations;
    private final Clock clock;
    private final ObjectMapper json;
    public CommandGateway(CommandTransport transport, MutationPlanner planner, OperationStore operations, Clock clock, ObjectMapper json) {
        this.transport = transport; this.planner = planner; this.operations = operations; this.clock = clock; this.json = json;
    }
    public ResponseEntity<OperationResponse> submit(long user, String key, EngagementCommand.Action action,
            Long video, UUID resource, String title, Integer position, UUID version) {
        UUID id;
        try {
            id = key == null ? UUID.randomUUID() : UUID.fromString(key);
            if (id.version() != 4) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be a version 4 UUID"); }
        var now = clock.instant();
        if (action == EngagementCommand.Action.HISTORY_ADD || action == EngagementCommand.Action.PLAYLIST_CREATE) resource = TimeUuids.at(now);
        var command = new EngagementCommand(1, id, user, action, video, resource, title, position, version, now);
        // Finished retries must return their original receipt even if the resource was subsequently deleted.
        var existing = operations.find(user, id);
        OperationStore.Operation operation;
        if (existing.isEmpty()) {
            planner.validate(command);
            // Reserve the key with LWT before sending: concurrent requests cannot use it for different intents.
            // This inbox row is not a business-data write. Only the consumer changes the user's library.
            operation = operations.register(command);
        } else {
            operation = existing.get();
        }
        if (!operation.command().sameIntent(command)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was used for another action");
        // Retry an uncertain send using the ORIGINAL envelope. A reserved key alone does not prove Kafka accepted it.
        if (operation.state().equals("PENDING")) transport.publish(operation.command());
        var accepted = response(operation);
        return ResponseEntity.accepted().location(URI.create("/api/engagement/operations/" + id))
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.RETRY_AFTER, "1").body(accepted);
    }
    public OperationResponse status(long user, UUID id) {
        // A broker-accepted message may not yet have reached ScyllaDB. Never expose another user's receipt.
        return operations.find(user, id).map(this::response).orElse(new OperationResponse(id, "PENDING", null, 0, null));
    }
    private OperationResponse response(OperationStore.Operation operation) {
        Object result = operation.state().equals("SUCCEEDED") && operation.plan() != null
                ? json.readTree(operation.plan().result()) : null;
        return new OperationResponse(operation.command().operationId(), operation.state(), result, operation.httpStatus(), operation.message());
    }
}
