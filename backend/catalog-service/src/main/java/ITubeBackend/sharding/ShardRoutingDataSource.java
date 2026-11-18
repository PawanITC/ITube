package ITubeBackend.sharding;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * One DataSource in front of all catalog databases: each new connection comes from the database chosen by
 * {@link ShardContext}. JPA, Spring Data and transactions all use this without any changes.
 * <p>
 * While the application starts (Hibernate checks the schema), connections without a shard go to shard 0.
 * Once it is running ({@link #enforceExplicitShard()}), a query without a shard is a bug and throws, so a missed
 * routing can't silently read only one database.
 */
public class ShardRoutingDataSource extends AbstractRoutingDataSource {
    private volatile boolean strict;

    public void enforceExplicitShard() {
        this.strict = true;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        Integer shard = ShardContext.current();
        if (shard != null) return shard;
        if (strict) {
            throw new IllegalStateException("Catalog query without a shard: wrap it in Shards.onShardOf / onOwnerShard / scatter");
        }
        return 0;
    }
}
