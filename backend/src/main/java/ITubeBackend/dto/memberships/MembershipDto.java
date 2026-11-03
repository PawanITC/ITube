package ITubeBackend.dto.memberships;

import java.time.OffsetDateTime;

public record MembershipDto(
        Long membershipId,
        Long channelId,
        String channelName,
        Long tierId,
        String tierName,
        int tierLevel,
        String status,
        boolean hasAccess,
        boolean cancelAtPeriodEnd,
        OffsetDateTime currentPeriodEnd) {
}
