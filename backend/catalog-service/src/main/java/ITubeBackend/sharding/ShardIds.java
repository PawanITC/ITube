package ITubeBackend.sharding;

/**
 * Catalog ids carry the shard they live on, so any channel or video can be found from its id alone, with no
 * lookup (the same idea as Instagram's and Pinterest's sharded ids):
 * <pre>
 *   id = logicalShard &lt;&lt; 40 | sequence
 *        \__ 12 bits __/     \__ 40 bits: per-database sequence (about 1.1 trillion ids per shard) __/
 * </pre>
 * <ul>
 *   <li>{@value #LOGICAL_SHARDS} logical shards, fixed forever (they're baked into every id). Logical shards are
 *       mapped onto however many physical databases you run ({@link ShardMap}); growing from 2 to 16 databases
 *       means moving logical shards, never re-numbering rows.</li>
 *   <li>The largest id is below 2^52, so ids stay exact as JavaScript numbers (2^53) in the browser.</li>
 *   <li>Ids created before sharding are small numbers, so their shard bits are 0: they live on logical shard 0,
 *       which is always on physical shard 0 (the original database). Nothing had to be migrated.</li>
 * </ul>
 */
public final class ShardIds {
    public static final int LOGICAL_SHARDS = 4096;
    public static final int SEQUENCE_BITS = 40;
    public static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final long MAX_ID = ((long) LOGICAL_SHARDS << SEQUENCE_BITS) - 1;

    private ShardIds() {
    }

    /** The logical shard an id lives on, or -1 if no valid catalog id looks like this. */
    public static int logicalShardOf(Long id) {
        if (id == null || id <= 0 || id > MAX_ID) return -1;
        return (int) (id >>> SEQUENCE_BITS);
    }

    public static long compose(int logicalShard, long sequence) {
        if (logicalShard < 0 || logicalShard >= LOGICAL_SHARDS) {
            throw new IllegalArgumentException("Logical shard out of range: " + logicalShard);
        }
        if (sequence <= 0 || sequence > MAX_SEQUENCE) {
            throw new IllegalStateException("Shard " + logicalShard + " ran out of ids (sequence " + sequence + ")");
        }
        return ((long) logicalShard << SEQUENCE_BITS) | sequence;
    }

    /**
     * Where a creator who has no channel yet will live. A hash spreads creators evenly over all logical shards
     * (the mixing step stops consecutive user ids from landing on consecutive shards).
     */
    public static int logicalShardForNewOwner(long userId) {
        long mixed = userId * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 31;
        return (int) Math.floorMod(mixed, (long) LOGICAL_SHARDS);
    }
}
