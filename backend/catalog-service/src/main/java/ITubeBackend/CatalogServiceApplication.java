package ITubeBackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * catalog-service: channels, subscriptions, video metadata, resumable uploads and playback. Owns the catalog
 * schema, sharded over several PostgreSQL databases (ITubeBackend.sharding), and the media bucket.
 * This is the hottest service (every page view and every play reads it), so it is the one with the most caching.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
