package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A resumable upload in progress: an object storage multipart upload plus the video details entered when it
 * started. The file is identified by name, size and last-modified time, so the browser can check that the
 * file chosen to resume is the same one.
 */
@Entity
@Table(name = "upload_session", schema = "catalog")
public class UploadSessionEntity {
    public static final String UPLOADING = "UPLOADING";
    /** The parts were joined into the source object, but the video row was not written yet. */
    public static final String ASSEMBLED = "ASSEMBLED";

    @Id
    @Column(name = "upload_session_id")
    private UUID uploadSessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "storage_upload_id", nullable = false, length = 1024)
    private String storageUploadId;

    @Column(name = "status", nullable = false, length = 20)
    private String status = UPLOADING;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "file_last_modified", nullable = false)
    private long fileLastModified;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "part_size", nullable = false)
    private long partSize;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "duration", nullable = false)
    private LocalTime duration;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private VideoVisibility visibility;

    @Column(name = "required_tier_level")
    private Integer requiredTierLevel;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected UploadSessionEntity() {
    }

    public UploadSessionEntity(UUID uploadSessionId, Long userId, Long channelId, String storageKey, String storageUploadId,
                               String fileName, long fileSize, long fileLastModified, String contentType, long partSize,
                               OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        this.uploadSessionId = uploadSessionId;
        this.userId = userId;
        this.channelId = channelId;
        this.storageKey = storageKey;
        this.storageUploadId = storageUploadId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileLastModified = fileLastModified;
        this.contentType = contentType;
        this.partSize = partSize;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void setDetails(String title, LocalTime duration, VideoVisibility visibility, Integer requiredTierLevel) {
        this.title = title;
        this.duration = duration;
        this.visibility = visibility;
        this.requiredTierLevel = requiredTierLevel;
    }

    public void setStatus(String status) { this.status = status; }

    /** Number of parts the file is split into; every part is partSize bytes except possibly the last. */
    public int partCount() {
        return (int) ((fileSize + partSize - 1) / partSize);
    }

    /** Size in bytes of part {@code partNumber} (1-based). */
    public long partLength(int partNumber) {
        return Math.min(partSize, fileSize - (long) (partNumber - 1) * partSize);
    }

    public UUID getUploadSessionId() { return uploadSessionId; }
    public Long getUserId() { return userId; }
    public Long getChannelId() { return channelId; }
    public String getStorageKey() { return storageKey; }
    public String getStorageUploadId() { return storageUploadId; }
    public String getStatus() { return status; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public long getFileLastModified() { return fileLastModified; }
    public String getContentType() { return contentType; }
    public long getPartSize() { return partSize; }
    public String getTitle() { return title; }
    public LocalTime getDuration() { return duration; }
    public VideoVisibility getVisibility() { return visibility; }
    public Integer getRequiredTierLevel() { return requiredTierLevel; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
