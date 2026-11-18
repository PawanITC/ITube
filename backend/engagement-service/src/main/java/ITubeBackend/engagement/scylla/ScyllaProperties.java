package ITubeBackend.engagement.scylla;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * contactPoints: comma-separated host:port list. replication: the CQL replication map used when the
 * keyspace is created, e.g. {'class': 'NetworkTopologyStrategy', 'replication_factor': 3} in production.
 */
@ConfigurationProperties(prefix = "itube.scylla")
public record ScyllaProperties(
        String contactPoints,
        String localDatacenter,
        String keyspace,
        String replication,
        String username,
        String password,
        boolean createSchema,
        Duration requestTimeout) {

    public ScyllaProperties {
        if (contactPoints == null || contactPoints.isBlank()) contactPoints = "localhost:9042";
        if (localDatacenter == null || localDatacenter.isBlank()) localDatacenter = "datacenter1";
        if (keyspace == null || keyspace.isBlank()) keyspace = "itube_engagement";
        if (!keyspace.matches("[A-Za-z_][A-Za-z0-9_]{0,47}")) {
            throw new IllegalArgumentException("Invalid itube.scylla.keyspace: " + keyspace);
        }
        if (replication == null || replication.isBlank()) {
            replication = "{'class': 'NetworkTopologyStrategy', 'replication_factor': 1}";
        }
        if (requestTimeout == null) requestTimeout = Duration.ofSeconds(5);
    }

    @Override
    public String toString() {
        return "ScyllaProperties[contactPoints=" + contactPoints + ", localDatacenter=" + localDatacenter
                + ", keyspace=" + keyspace + "]";
    }
}
