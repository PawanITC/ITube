package ITubeBackend.service.entitlement;

import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.repository.ChannelMembershipRepository;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Server-side answer to "may this user play this video?". Channel memberships only; iTube Premium
 * (Phase 3) is a separate entitlement and never unlocks creator content.
 */
@Service
public class EntitlementService {
    private final ChannelMembershipRepository membershipRepository;
    private final ChannelMembershipTierRepository tierRepository;
    private final MembershipAccessPolicy accessPolicy;

    public EntitlementService(ChannelMembershipRepository membershipRepository,
                              ChannelMembershipTierRepository tierRepository, MembershipAccessPolicy accessPolicy) {
        this.membershipRepository = membershipRepository;
        this.tierRepository = tierRepository;
        this.accessPolicy = accessPolicy;
    }

    public record PlaybackDecision(boolean allowed, String reason) {
        static PlaybackDecision allow() { return new PlaybackDecision(true, null); }
        static PlaybackDecision deny(String reason) { return new PlaybackDecision(false, reason); }
    }

    @Transactional(readOnly = true)
    public PlaybackDecision decide(Long userId, VideoAccessDto video) {
        if (video.visibility().openToEveryone()) return PlaybackDecision.allow();
        boolean owner = userId != null && userId.equals(video.ownerUserId());
        if (owner) return PlaybackDecision.allow();
        return switch (video.visibility()) {
            case PRIVATE -> PlaybackDecision.deny("PRIVATE");
            case MEMBERS_ONLY -> decideMembersOnly(userId, video);
            default -> throw new IllegalStateException("Unhandled visibility " + video.visibility());
        };
    }

    private PlaybackDecision decideMembersOnly(Long userId, VideoAccessDto video) {
        if (userId == null) return PlaybackDecision.deny("SIGN_IN_REQUIRED");
        // Status and period end are checked live, so access ends on time even if a webhook is late
        Optional<ChannelMembershipEntity> membership = membershipRepository.findLive(userId, video.channelId())
                .filter(m -> accessPolicy.hasAccess(m.getStatus(), m.getCurrentPeriodEnd()));
        if (membership.isEmpty()) return PlaybackDecision.deny("MEMBERSHIP_REQUIRED");
        int requiredLevel = video.requiredTierLevel() == null ? 1 : video.requiredTierLevel();
        int memberLevel = tierRepository.findById(membership.get().getTierId()).map(t -> t.getLevel()).orElse(0);
        return memberLevel >= requiredLevel ? PlaybackDecision.allow() : PlaybackDecision.deny("HIGHER_TIER_REQUIRED");
    }
}
