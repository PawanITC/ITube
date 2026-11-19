package ITubeBackend.dto.memberships;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Null fields are left unchanged. A new price only applies to new members; existing members keep theirs. */
public record UpdateTierRequest(
        @Size(min = 1, max = 80) String name,
        @Size(max = 500) String description,
        @Positive Long priceMinor,
        @Valid @Size(max = 20) List<PerkRequest> perks) {
}
