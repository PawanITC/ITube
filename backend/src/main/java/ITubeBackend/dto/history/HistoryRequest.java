package ITubeBackend.dto.history;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record HistoryRequest(@NotNull @Positive Long videoId) {
}
