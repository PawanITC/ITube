package ITubeBackend.dto.history;

import java.time.Instant;

public class HistoryResponse {

    private final Long id;
    private final Long videoId;
    private final Instant watchedAt;

    public HistoryResponse(Long id, Long videoId, Instant watchedAt) {
        this.id = id;
        this.videoId = videoId;
        this.watchedAt = watchedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getVideoId() {
        return videoId;
    }

    public Instant getWatchedAt() {
        return watchedAt;
    }
}
