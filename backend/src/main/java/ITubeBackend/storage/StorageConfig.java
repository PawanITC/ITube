package ITubeBackend.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class StorageConfig {
    @Bean
    ObjectStorage objectStorage(StorageProperties properties) {
        return switch (properties.type()) {
            case "s3" -> new S3ObjectStorage(properties);
            case "local" -> new LocalObjectStorage(properties.localDir() == null || properties.localDir().isBlank()
                    ? Path.of(System.getProperty("java.io.tmpdir"), "itube-media")
                    : Path.of(properties.localDir()));
            default -> throw new IllegalStateException("Unknown itube.storage.type: " + properties.type());
        };
    }
}
