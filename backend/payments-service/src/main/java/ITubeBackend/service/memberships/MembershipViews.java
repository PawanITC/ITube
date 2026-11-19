package ITubeBackend.service.memberships;

import ITubeBackend.dto.memberships.MembershipDto;
import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.client.DownstreamUnavailableException;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.entities.ChannelMembershipTierEntity;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import ITubeBackend.service.entitlement.MembershipAccessPolicy;
import org.springframework.stereotype.Component;

/** Builds membership DTOs. hasAccess is computed live, so it reflects expiry even before a webhook arrives. */
@Component
public class MembershipViews {
    private final CatalogClient catalog;
    private final ChannelMembershipTierRepository tierRepository;
    private final MembershipAccessPolicy accessPolicy;

    public MembershipViews(CatalogClient catalog, ChannelMembershipTierRepository tierRepository,
                           MembershipAccessPolicy accessPolicy) {
        this.catalog = catalog;
        this.tierRepository = tierRepository;
        this.accessPolicy = accessPolicy;
    }

    public MembershipDto toDto(ChannelMembershipEntity membership) {
        String channelName = channelName(membership.getChannelId());
        ChannelMembershipTierEntity tier = tierRepository.findById(membership.getTierId()).orElseThrow();
        return new MembershipDto(membership.getMembershipId(), membership.getChannelId(), channelName,
                tier.getTierId(), tier.getName(), tier.getLevel(), membership.getStatus(),
                accessPolicy.hasAccess(membership.getStatus(), membership.getCurrentPeriodEnd()),
                membership.isCancelAtPeriodEnd(), membership.getCurrentPeriodEnd());
    }

    /** Display only, so a catalog outage shows a placeholder instead of failing the memberships page. */
    private String channelName(Long channelId) {
        try {
            return catalog.channel(channelId).map(ChannelDto::channelName).orElse("Deleted channel");
        } catch (DownstreamUnavailableException exception) {
            return "Channel " + channelId;
        }
    }
}
