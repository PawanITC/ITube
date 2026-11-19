package ITubeBackend.payments;

import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.entities.ChannelMembershipTierEntity;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.ChannelMembershipRepository;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import ITubeBackend.service.entitlement.EntitlementService;
import ITubeBackend.service.entitlement.MembershipAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import static ITubeBackend.payments.StripeFixtures.CLOCK;
import static ITubeBackend.payments.StripeFixtures.inDays;
import static ITubeBackend.payments.StripeFixtures.paymentProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The membership rules behind /internal/entitlements/decide (catalog-service asks this for members-only videos). */
class EntitlementServiceTest {
    private static final long CREATOR = 99L;
    private static final long VIEWER = 7L;
    private static final long CHANNEL = 5L;

    private final ChannelMembershipRepository membershipRepository = mock(ChannelMembershipRepository.class);
    private final ChannelMembershipTierRepository tierRepository = mock(ChannelMembershipTierRepository.class);
    private EntitlementService entitlement;

    @BeforeEach
    void setUp() {
        entitlement = new EntitlementService(membershipRepository, tierRepository,
                new MembershipAccessPolicy(paymentProperties(true), CLOCK));
        when(tierRepository.findById(10L)).thenReturn(Optional.of(tier(10L, 1)));
    }

    @Test
    void nonMemberNeedsAMembershipAndSignedOutViewerMustSignIn() {
        assertThat(entitlement.decide(VIEWER, video(VideoVisibility.MEMBERS_ONLY, null)).reason()).isEqualTo("MEMBERSHIP_REQUIRED");
        assertThat(entitlement.decide(null, video(VideoVisibility.MEMBERS_ONLY, null)).reason()).isEqualTo("SIGN_IN_REQUIRED");
    }

    @Test
    void activeMemberIsAllowed() {
        member("active", inDays(20));
        assertThat(entitlement.decide(VIEWER, video(VideoVisibility.MEMBERS_ONLY, null)).allowed()).isTrue();
    }

    @Test
    void lowerTierMemberIsAskedToUpgrade() {
        member("active", inDays(20));
        assertThat(entitlement.decide(VIEWER, video(VideoVisibility.MEMBERS_ONLY, 3)).reason()).isEqualTo("HIGHER_TIER_REQUIRED");
    }

    @Test
    void expiredMembershipLosesAccessEvenBeforeAWebhookArrives() {
        member("active", inDays(-3)); // period ended 3 days ago, beyond the 24h grace
        assertThat(entitlement.decide(VIEWER, video(VideoVisibility.MEMBERS_ONLY, null)).allowed()).isFalse();
    }

    @Test
    void ownerAndPublicAreAlwaysAllowedAndPrivateIsOwnerOnly() {
        assertThat(entitlement.decide(CREATOR, video(VideoVisibility.MEMBERS_ONLY, 3)).allowed()).isTrue();
        assertThat(entitlement.decide(null, video(VideoVisibility.PUBLIC, null)).allowed()).isTrue();
        assertThat(entitlement.decide(VIEWER, video(VideoVisibility.PRIVATE, null)).reason()).isEqualTo("PRIVATE");
    }

    private static VideoAccessDto video(VideoVisibility visibility, Integer level) {
        return new VideoAccessDto(1L, CHANNEL, CREATOR, visibility, level, LocalTime.of(0, 45));
    }

    private void member(String status, OffsetDateTime periodEnd) {
        ChannelMembershipEntity membership = new ChannelMembershipEntity(VIEWER, CHANNEL, "sub_1", "cus_1");
        membership.setTierId(10L);
        membership.setStatus(status);
        membership.setCurrentPeriodEnd(periodEnd);
        when(membershipRepository.findLive(VIEWER, CHANNEL)).thenReturn(Optional.of(membership));
    }

    private static ChannelMembershipTierEntity tier(Long id, int level) {
        ChannelMembershipTierEntity tier = new ChannelMembershipTierEntity();
        ReflectionTestUtils.setField(tier, "tierId", id);
        tier.setLevel(level);
        return tier;
    }
}
