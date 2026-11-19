package ITubeBackend.dto.memberships;

import jakarta.validation.constraints.NotNull;

public record ChangeTierRequest(@NotNull Long tierId) {
}
