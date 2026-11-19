package ITubeBackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * engagement-service: watch history, likes and comments. Owns the ScyllaDB keyspace itube_engagement.
 * Video details come from catalog-service (cached); the user and their display name come from the access token (JWT).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EngagementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EngagementServiceApplication.class, args);
    }
}
