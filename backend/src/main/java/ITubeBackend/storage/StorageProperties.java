package ITubeBackend.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * type: "s3" (default) or "local".
 * endpoint: S3 API endpoint the backend uses (blank = AWS). publicEndpoint: the host browsers are sent to
 * by pre-signed URLs (e.g. a CDN or http://localhost:8333 for SeaweedFS); blank = same as endpoint.
 * accessKey/secretKey: blank = the AWS default credential chain (env vars, instance/pod role).
 * redirectPlayback: true sends viewers to a pre-signed URL; false streams through the backend.
 */
@ConfigurationProperties(prefix = "itube.storage")
public record StorageProperties(
        String type,
        String bucket,
        String endpoint,
        String publicEndpoint,
        String region,
        String accessKey,
        String secretKey,
        boolean pathStyle,
        boolean createBucket,
        String localDir,
        Duration presignTtl,
        boolean redirectPlayback) {

    public StorageProperties {
        if (type == null || type.isBlank()) type = "s3";
        if (bucket == null || bucket.isBlank()) bucket = "itube-media";
        if (region == null || region.isBlank()) region = "us-east-1";
        if (presignTtl == null) presignTtl = Duration.ofHours(6);
    }

    @Override
    public String toString() {
        return "StorageProperties[type=" + type + ", bucket=" + bucket + ", endpoint=" + endpoint + "]";
    }
}
