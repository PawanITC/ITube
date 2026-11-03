package ITubeBackend.payments;

import ITubeBackend.config.PlaybackProperties;
import ITubeBackend.dto.video.PlaybackAuthorisationDto;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.entities.ChannelMembershipTierEntity;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.ChannelMembershipRepository;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.service.entitlement.EntitlementService;
import ITubeBackend.service.entitlement.MembershipAccessPolicy;
import ITubeBackend.service.entitlement.PlaybackTokenService;
import ITubeBackend.service.video.VideoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import static ITubeBackend.payments.StripeFixtures.CLOCK;
import static ITubeBackend.payments.StripeFixtures.inDays;
import static ITubeBackend.payments.StripeFixtures.paymentProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MembersOnlyPlaybackTest {
    private static final long CREATOR = 99L;
    private static final long VIEWER = 7L;
    private static final long CHANNEL = 5L;

    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final ChannelMembershipRepository membershipRepository = mock(ChannelMembershipRepository.class);
    private final ChannelMembershipTierRepository tierRepository = mock(ChannelMembershipTierRepository.class);
    private VideoService videoService;

    @BeforeEach
    void setUp() {
        EntitlementService entitlement = new EntitlementService(membershipRepository, tierRepository,
                new MembershipAccessPolicy(paymentProperties(true), CLOCK));
        PlaybackTokenService tokens = new PlaybackTokenService(
                new PlaybackProperties(Base64.getEncoder().encodeToString(new byte[32]), Duration.ofMinutes(10), Duration.ofHours(6)), CLOCK);
        videoService = new VideoService(mock(ChannelRepository.class), videoRepository,
                mock(ApplicationEventPublisher.class), entitlement, tokens);
        video(1L, VideoVisibility.PUBLIC, null);
        video(2L, VideoVisibility.MEMBERS_ONLY, null);
        video(3L, VideoVisibility.MEMBERS_ONLY, 3);
        video(4L, VideoVisibility.PRIVATE, null);
        when(tierRepository.findById(10L)).thenReturn(Optional.of(tier(10L, 1)));
    }

    @Test
    void directRequestForMembersOnlyFileWithoutTokenIsRefused() {
        // Hiding the player isn't the protection: the media endpoint itself refuses
        assertThatThrownBy(() -> videoService.assertCanStream(2L, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void publicVideosStreamWithoutAToken() {
        assertThat(videoService.assertCanStream(1L, null)).isTrue();
    }

    @Test
    void nonMemberIsToldMembershipIsRequiredAndGetsNoToken() {
        PlaybackAuthorisationDto result = videoService.authorisePlayback(2L, VIEWER);
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("MEMBERSHIP_REQUIRED");
        assertThat(result.token()).isNull();
        assertThat(videoService.authorisePlayback(2L, null).reason()).isEqualTo("SIGN_IN_REQUIRED");
    }

    @Test
    void activeMemberGetsATokenThatUnlocksOnlyThatVideo() {
        member("active", inDays(20));

        PlaybackAuthorisationDto result = videoService.authorisePlayback(2L, VIEWER);

        assertThat(result.allowed()).isTrue();
        assertThat(videoService.assertCanStream(2L, result.token())).isFalse();
        assertThatThrownBy(() -> videoService.assertCanStream(3L, result.token())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void lowerTierMemberIsAskedToUpgrade() {
        member("active", inDays(20));
        assertThat(videoService.authorisePlayback(3L, VIEWER).reason()).isEqualTo("HIGHER_TIER_REQUIRED");
    }

    @Test
    void expiredMembershipLosesAccessEvenBeforeAWebhookArrives() {
        member("active", inDays(-3)); // period ended 3 days ago, beyond the 24h grace
        assertThat(videoService.authorisePlayback(2L, VIEWER).allowed()).isFalse();
    }

    @Test
    void creatorCanAlwaysWatchTheirOwnVideos() {
        assertThat(videoService.authorisePlayback(3L, CREATOR).allowed()).isTrue();
        assertThat(videoService.authorisePlayback(4L, CREATOR).allowed()).isTrue();
    }

    @Test
    void privateVideosLookMissingToEveryoneElse() {
        assertThatThrownBy(() -> videoService.authorisePlayback(4L, VIEWER))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void video(Long id, VideoVisibility visibility, Integer level) {
        when(videoRepository.findAccess(id)).thenReturn(Optional.of(new VideoAccessDto(id, CHANNEL, CREATOR, visibility, level, java.time.LocalTime.of(0, 45))));
    }

    private void member(String status, java.time.OffsetDateTime periodEnd) {
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
