package ITubeBackend.dto.memberships;

import java.util.List;

/** priceMinor is in minor units (pence). perks include those inherited from lower tiers. */
public record TierDto(
        Long tierId,
        Long channelId,
        String name,
        String description,
        int level,
        long priceMinor,
        String currency,
        List<PerkDto> perks) {
}
