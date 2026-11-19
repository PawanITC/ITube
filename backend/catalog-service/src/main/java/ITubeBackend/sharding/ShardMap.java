package ITubeBackend.sharding;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Which physical database holds which logical shards. Physical shard i holds the contiguous range
 * [first(i), first(i+1)). With 2 databases and the default even split:
 * <pre>
 *   physical 0: logical 0 .. 2047      (includes logical 0 = every pre-sharding row)
 *   physical 1: logical 2048 .. 4095
 * </pre>
 */
public final class ShardMap {
    private final int[] firstLogical;

    public ShardMap(ShardingProperties properties) {
        int count = properties.shards().size();
        if (count > ShardIds.LOGICAL_SHARDS) throw new IllegalStateException("More databases than logical shards");
        firstLogical = new int[count];
        for (int i = 0; i < count; i++) {
            Integer configured = properties.shards().get(i).firstLogicalShard();
            firstLogical[i] = configured != null ? configured : (int) ((long) i * ShardIds.LOGICAL_SHARDS / count);
        }
        if (firstLogical[0] != 0) {
            throw new IllegalStateException("itube.sharding.shards[0] must start at logical shard 0 (it holds pre-sharding rows)");
        }
        for (int i = 1; i < count; i++) {
            if (firstLogical[i] <= firstLogical[i - 1] || firstLogical[i] >= ShardIds.LOGICAL_SHARDS) {
                throw new IllegalStateException("itube.sharding.shards[*].first-logical-shard must increase and stay below "
                        + ShardIds.LOGICAL_SHARDS);
            }
        }
    }

    public int physicalCount() {
        return firstLogical.length;
    }

    public List<Integer> physicalShards() {
        return IntStream.range(0, firstLogical.length).boxed().toList();
    }

    public int physicalOf(int logicalShard) {
        if (logicalShard < 0 || logicalShard >= ShardIds.LOGICAL_SHARDS) {
            throw new IllegalArgumentException("Logical shard out of range: " + logicalShard);
        }
        int physical = 0;
        for (int i = 1; i < firstLogical.length && firstLogical[i] <= logicalShard; i++) physical = i;
        return physical;
    }

    /** For docs/diagnostics: "0-2047", "2048-4095". */
    public List<String> ranges() {
        List<String> ranges = new ArrayList<>();
        for (int i = 0; i < firstLogical.length; i++) {
            int end = i + 1 < firstLogical.length ? firstLogical[i + 1] - 1 : ShardIds.LOGICAL_SHARDS - 1;
            ranges.add(firstLogical[i] + "-" + end);
        }
        return ranges;
    }
}
