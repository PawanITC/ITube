package ITubeBackend.service.memberships;

import ITubeBackend.dto.memberships.MembershipDto;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.entities.ChannelMembershipTierEntity;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.service.entitlement.MembershipAccessPolicy;
import org.springframework.stereotype.Component;

/** Builds membership DTOs. hasAccess is computed live, so it reflects expiry even before a webhook arrives. */
@Component
public class MembershipViews {
    private final ChannelRepository channelRepository;
    private final ChannelMembershipTierRepository tierRepository;
    private final MembershipAccessPolicy accessPolicy;

    public MembershipViews(ChannelRepository channelRepository, ChannelMembershipTierRepository tierRepository,
                           MembershipAccessPolicy accessPolicy) {
        this.channelRepository = channelRepository;
        this.tierRepository = tierRepository;
        this.accessPolicy = accessPolicy;
    }

    public MembershipDto toDto(ChannelMembershipEntity membership) {
        String channelName = channelRepository.findById(membership.getChannelId())
                .map(ChannelEntity::getChannelName).orElse("Deleted channel");
        ChannelMembershipTierEntity tier = tierRepository.findById(membership.getTierId()).orElseThrow();
        return new MembershipDto(membership.getMembershipId(), membership.getChannelId(), channelName,
                tier.getTierId(), tier.getName(), tier.getLevel(), membership.getStatus(),
                accessPolicy.hasAccess(membership.getStatus(), membership.getCurrentPeriodEnd()),
                membership.isCancelAtPeriodEnd(), membership.getCurrentPeriodEnd());
    }
}
