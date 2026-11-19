package ITubeBackend.sharding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hands out new channel and video ids on a logical shard: {@code logicalShard << 40 | nextval(sequence)}.
 * Each database has its own sequence; different logical shards on the same database share it, which is fine
 * because the shard bits keep their ids apart. Must run on the shard that will store the row.
 * <p>
 * On PostgreSQL this uses the id column's own identity sequence, so on the original database new ids simply
 * continue after the pre-sharding ones. H2 (tests) uses catalog.channel_ids / catalog.video_ids.
 */
@Component
public class IdAllocator {
    private final JdbcTemplate jdbc;
    private final ShardMap shardMap;
    private final ShardedDataSourceConfig.ShardDatabases databases;

    public IdAllocator(ShardRoutingDataSource dataSource, ShardMap shardMap, ShardedDataSourceConfig.ShardDatabases databases) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.shardMap = shardMap;
        this.databases = databases;
    }

    public long nextChannelId(int logicalShard) {
        return ShardIds.compose(logicalShard, next(logicalShard, "catalog.channel", "channel_id", "catalog.channel_ids"));
    }

    public long nextVideoId(int logicalShard) {
        return ShardIds.compose(logicalShard, next(logicalShard, "catalog.video", "video_id", "catalog.video_ids"));
    }

    private long next(int logicalShard, String table, String column, String h2Sequence) {
        int physical = shardMap.physicalOf(logicalShard);
        Integer current = ShardContext.current();
        if (current == null || current != physical) {
            throw new IllegalStateException("Allocate ids for logical shard " + logicalShard + " on physical shard "
                    + physical + ", not " + current);
        }
        String sql = Boolean.TRUE.equals(databases.postgres().get(physical))
                ? "SELECT nextval(pg_get_serial_sequence('" + table + "', '" + column + "'))"
                : "SELECT NEXT VALUE FOR " + h2Sequence;
        Long value = jdbc.queryForObject(sql, Long.class);
        if (value == null) throw new IllegalStateException("No id from " + table);
        return value;
    }
}
