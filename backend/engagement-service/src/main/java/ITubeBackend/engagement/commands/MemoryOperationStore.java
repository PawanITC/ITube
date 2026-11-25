package ITubeBackend.engagement.commands;

import java.util.*;

/** Test double only; production receipts and clocks live in ScyllaDB. */
public class MemoryOperationStore implements OperationStore {
    private record Key(long userId, UUID id) { }
    private final Map<Key, Operation> rows = new HashMap<>();
    private final Map<Long, Long> clocks = new HashMap<>();
    private Key key(EngagementCommand c) { return new Key(c.userId(), c.operationId()); }
    public synchronized Operation register(EngagementCommand c) {
        return rows.computeIfAbsent(key(c), ignored -> new Operation(c, "PENDING", null, 0, null));
    }
    public synchronized Optional<Operation> find(long user, UUID id) { return Optional.ofNullable(rows.get(new Key(user, id))); }
    public synchronized MutationPlan prepare(EngagementCommand c, MutationPlan plan) {
        Operation old = rows.get(key(c));
        if (old.plan() != null) return old.plan();
        rows.put(key(c), new Operation(old.command(), old.state(), plan, old.httpStatus(), old.message()));
        return plan;
    }
    public synchronized void finish(EngagementCommand c, String state, int status, String message) {
        Operation old = rows.get(key(c));
        if (old.state().equals("PENDING")) rows.put(key(c), new Operation(old.command(), state, old.plan(), status, message));
    }
    public synchronized long nextWriteMicros(long user, long wall) {
        return clocks.compute(user, (id, old) -> Math.max(wall, old == null ? 1 : old + 1));
    }
}
