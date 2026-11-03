package History;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Index;

@Entity
@Table(name = "watch_history", indexes = {
        @Index(name = "idx_history_user_watched_id", columnList = "userId, watchedAt, id")
})
public class WatchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private Long videoId;

    @Column(nullable = false)
    private Instant watchedAt;

    protected WatchHistory() {
    }

    public WatchHistory(String userId, Long videoId, Instant watchedAt) {
        this.userId = userId;
        this.videoId = videoId;
        this.watchedAt = watchedAt;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public Long getVideoId() {
        return videoId;
    }

    public Instant getWatchedAt() {
        return watchedAt;
    }
}
