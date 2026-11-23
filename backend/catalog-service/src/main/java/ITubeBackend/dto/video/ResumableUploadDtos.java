package ITubeBackend.dto.video;

import ITubeBackend.entities.VideoType;
import ITubeBackend.entities.VideoVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of /api/uploads (resumable video uploads). */
public final class ResumableUploadDtos {
    private ResumableUploadDtos() {
    }

    /**
     * Starts an upload, or returns the open upload of the same file (same name, size and last-modified time)
     * so picking the file again resumes instead of starting over.
     */
    public record StartUploadRequest(
            @NotBlank String fileName,
            @Positive long fileSize,
            @PositiveOrZero long lastModified,
            String contentType,
            @NotBlank String title,
            @NotNull LocalTime duration,
            VideoVisibility visibility,
            Integer requiredTierLevel,
            VideoType videoType,
            Boolean adsEnabled,
            /** How many ads, spread evenly; at most one per 10 minutes of video. Null means one. */
            Integer adCount,
            /** Optional, at most 5000 characters. */
            String description) {

        /** Without a description. */
        public StartUploadRequest(String fileName, long fileSize, long lastModified, String contentType, String title,
                                  LocalTime duration, VideoVisibility visibility, Integer requiredTierLevel,
                                  VideoType videoType, Boolean adsEnabled, Integer adCount) {
            this(fileName, fileSize, lastModified, contentType, title, duration, visibility, requiredTierLevel,
                    videoType, adsEnabled, adCount, null);
        }

        /** A normal video without ads. */
        public StartUploadRequest(String fileName, long fileSize, long lastModified, String contentType, String title,
                                  LocalTime duration, VideoVisibility visibility, Integer requiredTierLevel) {
            this(fileName, fileSize, lastModified, contentType, title, duration, visibility, requiredTierLevel,
                    VideoType.VIDEO, false, null, null);
        }
    }

    /**
     * An unfinished upload. The file is split into partCount parts of partSize bytes (the last may be
     * shorter); uploadedParts are the part numbers (1-based) object storage already holds.
     */
    public record UploadSessionDto(
            UUID uploadId,
            String fileName,
            long fileSize,
            long lastModified,
            String contentType,
            long partSize,
            int partCount,
            List<Integer> uploadedParts,
            long uploadedBytes,
            String title,
            String description,
            LocalTime duration,
            VideoVisibility visibility,
            Integer requiredTierLevel,
            VideoType videoType,
            boolean adsEnabled,
            int adCount,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {
    }

    public record PartUrlsRequest(@NotEmpty List<Integer> partNumbers) {
    }

    /**
     * Where to PUT one part's bytes. viaBackend: the URL is this API (stores that can't pre-sign), so send
     * cookies; otherwise it is a pre-signed object storage URL, so don't.
     */
    public record PartUrlDto(int partNumber, String url, boolean viaBackend, OffsetDateTime expiresAt) {
    }

    public record CompletedUploadDto(Long videoId) {
    }
}
