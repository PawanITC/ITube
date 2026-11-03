package ITubeBackend.dto.memberships;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** priceMinor is the monthly price in pence; allowed range is set by itube.payments.memberships.*-price-minor. */
public record CreateTierRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @NotNull @Min(1) @Max(10) Integer level,
        @NotNull @Positive Long priceMinor,
        @Valid @Size(max = 20) List<PerkRequest> perks) {
}
