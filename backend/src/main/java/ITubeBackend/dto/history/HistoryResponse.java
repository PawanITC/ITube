package ITubeBackend.dto.history;

import java.time.Instant;
import ITubeBackend.dto.video.VideoSummaryDto;

public record HistoryResponse(Long id, Long videoId, Instant watchedAt, VideoSummaryDto video) {
}
