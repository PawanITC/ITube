package ITubeBackend.dto.video;

import jakarta.validation.constraints.*;
import java.util.List;

public record RecommendationCandidateRequest(
        @NotNull @Size(max = 20) List<@NotNull @Positive Long> preferredChannelIds,
        @NotNull @Size(max = 500) List<@NotNull @Positive Long> excludedVideoIds,
        @Min(1) @Max(500) int limit) { }
