package ITubeBackend.dto.video;

import org.springframework.core.io.InputStreamSource;

/** An uploaded file that is streamed to object storage rather than loaded into memory. */
public record MediaUpload(InputStreamSource source, long size, String contentType) {
}
