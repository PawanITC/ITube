package ITubeBackend.sharding;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * itube.sharding.* - the physical catalog databases.
 * <pre>
 * itube.sharding.shards[0].url=jdbc:postgresql://db-0:5432/itube        # must be the original database
 * itube.sharding.shards[1].url=jdbc:postgresql://db-1:5432/itube
 * </pre>
 * firstLogicalShard: where this database's range of logical shards starts (the range ends where the next
 * database's starts). Leave it unset to split the {@value ShardIds#LOGICAL_SHARDS} logical shards evenly.
 * Once data exists, ranges can only change together with moving that data (see docs/SHARDING.md).
 */
@ConfigurationProperties(prefix = "itube.sharding")
public record ShardingProperties(List<Shard> shards, Duration scatterTimeout, boolean backfillDirectory,
                                 int maximumPoolSize) {

    public ShardingProperties {
        if (shards == null || shards.isEmpty()) {
            throw new IllegalStateException("Configure at least one catalog database: itube.sharding.shards[0].url");
        }
        if (scatterTimeout == null) scatterTimeout = Duration.ofSeconds(2);
        if (maximumPoolSize < 1) maximumPoolSize = 20;
    }

    public record Shard(String url, String username, String password, Integer firstLogicalShard) {
    }
}
