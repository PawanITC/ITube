package ITubeBackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Resumable video uploads (itube.upload.*).
 * maxFileSize: largest video accepted. uploadTtl: how long an unfinished upload can be resumed; keep it at or
 * below the bucket's abort-incomplete-multipart-upload rule. partUrlTtl: lifetime of each pre-signed part URL.
 * maxOpenUploads: unfinished uploads one user may have at once.
 */
@ConfigurationProperties(prefix = "itube.upload")
public record UploadProperties(DataSize maxFileSize, Duration uploadTtl, Duration partUrlTtl, Integer maxOpenUploads) {
    public UploadProperties {
        if (maxFileSize == null) maxFileSize = DataSize.ofGigabytes(20);
        if (uploadTtl == null) uploadTtl = Duration.ofDays(7);
        if (partUrlTtl == null) partUrlTtl = Duration.ofHours(1);
        if (maxOpenUploads == null) maxOpenUploads = 10;
    }
}
