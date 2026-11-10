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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Index;

import java.time.Instant;

@Entity
@Table(name = "liked_video", indexes = {
        @Index(name = "idx_liked_video_user_time", columnList = "user_id, liked_at DESC, liked_video_id DESC")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_liked_video_user_video", columnNames = {"user_id", "video_id"})
})

public class LikedVideoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "liked_video_id")
    private Long likedVideoId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AccountEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", nullable = false)
    private VideoEntity video;

    @Column(name = "liked_at", nullable = false)
    private Instant likedAt;

    protected LikedVideoEntity() {
    }

    public LikedVideoEntity(AccountEntity user, VideoEntity video, Instant likedAt) {
        this.user = user;
        this.video = video;
        this.likedAt = likedAt;
    }

    public Long getLikedVideoId() {
        return likedVideoId;
    }

    public AccountEntity getUser() {
        return user;
    }

    public VideoEntity getVideo() {
        return video;
    }

    public Instant getLikedAt() {
        return likedAt;
    }
}
