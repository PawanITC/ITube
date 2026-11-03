package ITubeBackend.dto.video;

import ITubeBackend.entities.VideoVisibility;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** requiredTierLevel only applies to MEMBERS_ONLY; null means any tier. */
public record UpdateVisibilityRequest(@NotNull VideoVisibility visibility, @Min(1) @Max(10) Integer requiredTierLevel) {
}
