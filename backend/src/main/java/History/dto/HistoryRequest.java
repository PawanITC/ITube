package History.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class HistoryRequest {

    @NotNull
    @Positive
    private Long videoId;

    public HistoryRequest() {
    }

    public Long getVideoId() {
        return videoId;
    }

    public void setVideoId(Long videoId) {
        this.videoId = videoId;
    }
}
