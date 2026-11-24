package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * Video metadata. The file, renditions and thumbnail live in object storage; only their keys are kept here.
 * Lives on its channel's shard; the id (from {@link ITubeBackend.sharding.IdAllocator}) encodes that shard.
 */
@Entity
@Table(name = "video", schema = "catalog")
public class VideoEntity implements Persistable<Long> {
    @Id
    @Column(name = "video_id")
    private Long videoId;

    @Transient
    private boolean isNew = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private ChannelEntity channel;

    @Column(name = "video_key", length = 512)
    private String videoKey;

    @Column(name = "video_size_bytes")
    private Long videoSizeBytes;

    @Column(name = "video_content_type", nullable = false, length = 100)
    private String videoContentType;

    @Column(name = "thumbnail_key", length = 512)
    private String thumbnailKey;

    @Column(name = "thumbnail_content_type", length = 100)
    private String thumbnailContentType;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "duration", nullable = false)
    private LocalTime duration;

    /** Optional; null when the uploader left it empty. */
    @Column(name = "description", length = 5000)
    private String description;

    /** Only ever incremented in the database (VideoRepository.incrementViewCount), never written from here. */
    @Column(name = "view_count", nullable = false, insertable = false, updatable = false)
    private long viewCount;

    @Column(name = "processing_status", nullable = false, length = 20)
    private String processingStatus = "PROCESSING";

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private VideoVisibility visibility = VideoVisibility.PUBLIC;

    @Column(name = "required_tier_level")
    private Integer requiredTierLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "video_type", nullable = false, length = 10)
    private VideoType videoType = VideoType.VIDEO;

    /** Whether ads may play inside this video. Always false for ads. */
    @Column(name = "ads_enabled", nullable = false)
    private boolean adsEnabled;

    /** How many ads play in it when adsEnabled, spread evenly. */
    @Column(name = "ad_count", nullable = false)
    private int adCount = 1;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "transcode_attempts", nullable = false)
    private int transcodeAttempts;

    @Column(name = "transcode_requested_at")
    private OffsetDateTime transcodeRequestedAt;

    /** HlsBackfillJob: how often this pre-HLS video was queued for HLS transcoding, and when last. */
    @Column(name = "hls_backfill_attempts", nullable = false)
    private int hlsBackfillAttempts;

    @Column(name = "hls_backfill_requested_at")
    private OffsetDateTime hlsBackfillRequestedAt;

    public VideoEntity() {
    }

    public VideoEntity(Long videoId) {
        this.videoId = videoId;
    }

    @Override
    public Long getId() { return videoId; }

    /** Assigned ids: tell Spring Data to INSERT (persist) rather than look the row up first (merge). */
    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }

    public void setChannel(ChannelEntity channel) { this.channel = channel; }
    public void setVideoKey(String videoKey) { this.videoKey = videoKey; }
    public void setVideoSizeBytes(Long videoSizeBytes) { this.videoSizeBytes = videoSizeBytes; }
    public void setVideoContentType(String videoContentType) { this.videoContentType = videoContentType; }
    public void setThumbnailKey(String thumbnailKey) { this.thumbnailKey = thumbnailKey; }
    public void setThumbnailContentType(String thumbnailContentType) { this.thumbnailContentType = thumbnailContentType; }
    public void setTitle(String title) { this.title = title; }
    public void setDuration(LocalTime duration) { this.duration = duration; }
    public void setDescription(String description) { this.description = description; }
    public void setProcessingStatus(String processingStatus) { this.processingStatus = processingStatus; }
    public void setVisibility(VideoVisibility visibility) { this.visibility = visibility; }
    public void setRequiredTierLevel(Integer requiredTierLevel) { this.requiredTierLevel = requiredTierLevel; }
    public void setVideoType(VideoType videoType) { this.videoType = videoType; }
    public void setAdsEnabled(boolean adsEnabled) { this.adsEnabled = adsEnabled; }
    public void setAdCount(int adCount) { this.adCount = adCount; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public void setTranscodeAttempts(int transcodeAttempts) { this.transcodeAttempts = transcodeAttempts; }
    public void setTranscodeRequestedAt(OffsetDateTime transcodeRequestedAt) { this.transcodeRequestedAt = transcodeRequestedAt; }
    public void setHlsBackfillAttempts(int hlsBackfillAttempts) { this.hlsBackfillAttempts = hlsBackfillAttempts; }
    public void setHlsBackfillRequestedAt(OffsetDateTime hlsBackfillRequestedAt) { this.hlsBackfillRequestedAt = hlsBackfillRequestedAt; }

    public Long getVideoId() { return videoId; }
    public String getVideoKey() { return videoKey; }
    public Long getVideoSizeBytes() { return videoSizeBytes; }
    public String getThumbnailKey() { return thumbnailKey; }
    public String getThumbnailContentType() { return thumbnailContentType; }
    public String getVideoContentType() { return videoContentType; }
    public String getTitle() { return title; }
    public LocalTime getDuration() { return duration; }
    public String getDescription() { return description; }
    public long getViewCount() { return viewCount; }
    public ChannelEntity getChannel() { return channel; }
    public String getProcessingStatus() { return processingStatus; }
    public VideoVisibility getVisibility() { return visibility; }
    public Integer getRequiredTierLevel() { return requiredTierLevel; }
    public VideoType getVideoType() { return videoType; }
    public boolean isAdsEnabled() { return adsEnabled; }
    public int getAdCount() { return adCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public int getTranscodeAttempts() { return transcodeAttempts; }
    public OffsetDateTime getTranscodeRequestedAt() { return transcodeRequestedAt; }
    public int getHlsBackfillAttempts() { return hlsBackfillAttempts; }
    public OffsetDateTime getHlsBackfillRequestedAt() { return hlsBackfillRequestedAt; }
}
