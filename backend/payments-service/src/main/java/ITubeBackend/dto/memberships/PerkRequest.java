package ITubeBackend.dto.memberships;

import ITubeBackend.entities.PerkType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PerkRequest(@NotNull PerkType type, @NotBlank @Size(max = 255) String description) {
}
