package ITubeBackend.dto.history;

import java.time.Instant;
import java.util.UUID;
import ITubeBackend.dto.video.VideoSummaryDto;

/** id is a time-based UUID (the ScyllaDB clustering key), sent as a string. */
public record HistoryResponse(UUID id, Long videoId, Instant watchedAt, VideoSummaryDto video) {
}
