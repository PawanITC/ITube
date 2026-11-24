package ITubeBackend.engagement.commands;

import java.util.Optional;
import java.util.UUID;

/** Durable inbox/receipts, retained so replaying completed operations cannot restore removed data. */
public interface OperationStore {
    record Operation(EngagementCommand command, String state, MutationPlan plan, int httpStatus, String message) { }
    Operation register(EngagementCommand command);
    Optional<Operation> find(long userId, UUID operationId);
    MutationPlan prepare(EngagementCommand command, MutationPlan plan);
    void finish(EngagementCommand command, String state, int status, String message);
    long nextWriteMicros(long userId, long wallMicros);
}
