package ITubeBackend.sharding;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

/**
 * The physical shard the current thread talks to. {@link ShardRoutingDataSource} reads it whenever a database
 * connection is opened, so everything inside {@code ShardContext.on(1, ...)} - repositories, JPQL, transactions -
 * runs against database 1 without knowing it.
 * <p>
 * A transaction is pinned to one connection, so it can never span shards. Trying to switch shard inside an open
 * transaction fails loudly instead of silently querying the wrong database.
 */
public final class ShardContext {
    private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();

    private ShardContext() {
    }

    public static Integer current() {
        return CURRENT.get();
    }

    public static <T> T on(int physicalShard, Supplier<T> work) {
        Integer previous = CURRENT.get();
        if (previous != null && previous != physicalShard
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Cannot switch from shard " + previous + " to shard " + physicalShard
                    + " inside a transaction; a transaction can't span databases");
        }
        CURRENT.set(physicalShard);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static void run(int physicalShard, Runnable work) {
        on(physicalShard, () -> {
            work.run();
            return null;
        });
    }
}
