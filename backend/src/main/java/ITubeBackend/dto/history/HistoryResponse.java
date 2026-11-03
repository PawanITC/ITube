package ITubeBackend.dto.history;

import java.time.Instant;

public record HistoryResponse(Long id, Long videoId, Instant watchedAt) {
}
