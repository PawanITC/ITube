package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalTime;

@Entity
@Table(name = "video")
public class VideoEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "video_id")
    private Long videoId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private ChannelEntity channel;

    @Column(name = "video", nullable = false, columnDefinition = "bytea")
    private byte[] video;

    @Column(name = "video_content_type", nullable = false, length = 100)
    private String videoContentType;

    @Column(name = "thumbnail", columnDefinition = "bytea")
    private byte[] thumbnail;

    @Column(name = "thumbnail_content_type", length = 100)
    private String thumbnailContentType;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "duration", nullable = false)
    private LocalTime duration;

    public VideoEntity() {
    }

    public void setChannel(ChannelEntity channel) { this.channel = channel; }
    public void setVideo(byte[] video) { this.video = video; }
    public void setVideoContentType(String videoContentType) { this.videoContentType = videoContentType; }
    public void setThumbnail(byte[] thumbnail) { this.thumbnail = thumbnail; }
    public void setThumbnailContentType(String thumbnailContentType) { this.thumbnailContentType = thumbnailContentType; }
    public void setTitle(String title) { this.title = title; }
    public void setDuration(LocalTime duration) { this.duration = duration; }

    public Long getVideoId() { return videoId; }
    public byte[] getVideo() { return video; }
    public byte[] getThumbnail() { return thumbnail; }
    public String getThumbnailContentType() { return thumbnailContentType; }
    public String getVideoContentType() { return videoContentType; }
    public String getTitle() { return title; }
    public LocalTime getDuration() { return duration; }
    public ChannelEntity getChannel() { return channel; }
}
