package ITubeBackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * transcode-worker: turns uploaded videos into 360p/720p renditions with FFmpeg. No database and no public API:
 * it only talks to Kafka and object storage, so it can be scaled (or crash) without affecting anything users touch.
 * The HTTP port only serves /actuator/health for Kubernetes.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TranscodeWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TranscodeWorkerApplication.class, args);
    }
}
