package ITubeBackend.engagement;

import ITubeBackend.engagement.memory.InMemoryEngagementStores;
import ITubeBackend.engagement.scylla.ScyllaCommentStore;
import ITubeBackend.engagement.scylla.ScyllaLikeStore;
import ITubeBackend.engagement.scylla.ScyllaProperties;
import ITubeBackend.engagement.scylla.ScyllaSchema;
import ITubeBackend.engagement.scylla.ScyllaWatchHistoryStore;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

/** itube.engagement.store: "scylla" (default) or "memory" (tests and the h2 profile). */
@Configuration
public class EngagementStoreConfig {

    @Configuration
    @ConditionalOnProperty(name = "itube.engagement.store", havingValue = "scylla", matchIfMissing = true)
    static class Scylla {
        @Bean(destroyMethod = "close")
        CqlSession cqlSession(ScyllaProperties properties) {
            DriverConfigLoader config = DriverConfigLoader.programmaticBuilder()
                    // Keep retrying at startup: in docker compose Scylla takes ~30s to accept connections
                    .withBoolean(DefaultDriverOption.RECONNECT_ON_INIT, true)
                    .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, properties.requestTimeout())
                    // Quorum in the local datacenter: a write survives one replica failing (with RF=3)
                    .withString(DefaultDriverOption.REQUEST_CONSISTENCY, DefaultConsistencyLevel.LOCAL_QUORUM.name())
                    .withString(DefaultDriverOption.REQUEST_SERIAL_CONSISTENCY, DefaultConsistencyLevel.LOCAL_SERIAL.name())
                    .build();
            CqlSessionBuilder builder = CqlSession.builder().withConfigLoader(config)
                    .withLocalDatacenter(properties.localDatacenter());
            for (String contactPoint : properties.contactPoints().split(",")) {
                String[] hostPort = contactPoint.trim().split(":");
                int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 9042;
                builder.addContactPoint(new InetSocketAddress(hostPort[0], port));
            }
            if (properties.username() != null && !properties.username().isBlank()) {
                builder.withAuthCredentials(properties.username(), properties.password());
            }
            CqlSession session = builder.build();
            if (properties.createSchema()) ScyllaSchema.apply(session, properties);
            return session;
        }

        @Bean
        WatchHistoryStore watchHistoryStore(CqlSession session, ScyllaProperties properties) {
            return new ScyllaWatchHistoryStore(session, properties.keyspace());
        }

        @Bean
        LikeStore likeStore(CqlSession session, ScyllaProperties properties) {
            return new ScyllaLikeStore(session, properties.keyspace());
        }

        @Bean
        CommentStore commentStore(CqlSession session, ScyllaProperties properties) {
            return new ScyllaCommentStore(session, properties.keyspace());
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "itube.engagement.store", havingValue = "memory")
    static class Memory {
        @Bean
        WatchHistoryStore watchHistoryStore() {
            return new InMemoryEngagementStores.History();
        }

        @Bean
        LikeStore likeStore() {
            return new InMemoryEngagementStores.Likes();
        }

        @Bean
        CommentStore commentStore() {
            return new InMemoryEngagementStores.Comments();
        }
    }
}
