package ITubeBackend.sharding;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one connection pool per catalog database and the routing DataSource in front of them, and creates/updates
 * the catalog schema on every database before anything (Hibernate validation, repositories) uses it.
 */
@Configuration
@EnableConfigurationProperties(ShardingProperties.class)
public class ShardedDataSourceConfig {
    private static final Logger log = LoggerFactory.getLogger(ShardedDataSourceConfig.class);

    @Bean
    ShardMap shardMap(ShardingProperties properties) {
        return new ShardMap(properties);
    }

    @Bean
    ShardDatabases shardDatabases(ShardingProperties properties, ShardMap shardMap) {
        Map<Integer, DataSource> pools = new HashMap<>();
        Map<Integer, Boolean> postgres = new HashMap<>();
        for (int i = 0; i < properties.shards().size(); i++) {
            ShardingProperties.Shard shard = properties.shards().get(i);
            HikariDataSource pool = new HikariDataSource();
            pool.setPoolName("catalog-shard-" + i);
            pool.setJdbcUrl(shard.url());
            pool.setUsername(shard.username());
            pool.setPassword(shard.password());
            pool.setMaximumPoolSize(properties.maximumPoolSize());
            // Fail fast when a shard is down so callers get a quick error (and scatter queries a partial result).
            // At startup every shard must be reachable (its schema is checked); Kubernetes restarts the pod until
            // they are.
            pool.setConnectionTimeout(2000);
            pools.put(i, pool);
            postgres.put(i, initialiseSchema(i, pool));
        }
        log.info("Catalog sharded over {} database(s); logical shard ranges {}", pools.size(), shardMap.ranges());
        return new ShardDatabases(pools, postgres);
    }

    @Bean
    @Primary
    ShardRoutingDataSource dataSource(ShardDatabases databases) {
        ShardRoutingDataSource routing = new ShardRoutingDataSource();
        routing.setTargetDataSources(new HashMap<>(databases.pools()));
        routing.setDefaultTargetDataSource(databases.pools().get(0));
        routing.setLenientFallback(false);
        routing.afterPropertiesSet();
        return routing;
    }

    /** From now on every catalog query must name its shard (see ShardRoutingDataSource). */
    @EventListener(ApplicationReadyEvent.class)
    void enforceExplicitShards(ApplicationReadyEvent event) {
        event.getApplicationContext().getBean(ShardRoutingDataSource.class).enforceExplicitShard();
    }

    /** The pools by physical shard, and whether each is PostgreSQL (else H2 in tests). */
    public record ShardDatabases(Map<Integer, DataSource> pools, Map<Integer, Boolean> postgres) {
    }

    /** Runs the idempotent catalog scripts on one database; returns whether it is PostgreSQL. */
    private static boolean initialiseSchema(int shard, DataSource dataSource) {
        boolean isPostgres;
        try (Connection connection = dataSource.getConnection()) {
            isPostgres = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (SQLException exception) {
            throw new IllegalStateException("Catalog shard " + shard + " is unreachable at startup", exception);
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        // Only the original database can hold tables from before the domain split
        List<String> scripts = isPostgres && shard == 0
                ? List.of("schema-catalog-migrate.sql", "schema-catalog.sql")
                : List.of("schema-catalog.sql");
        scripts.forEach(script -> populator.addScript(new ClassPathResource(script)));
        populator.execute(dataSource);
        return isPostgres;
    }
}
