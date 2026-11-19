package ITubeBackend.dto.memberships;

import java.util.List;

public record ChannelTiersDto(
        Long channelId,
        String channelName,
        boolean owner,
        List<TierDto> tiers,
        MembershipDto currentMembership) {
}
