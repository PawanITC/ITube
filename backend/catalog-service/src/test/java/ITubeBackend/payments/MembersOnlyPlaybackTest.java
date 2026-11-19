package ITubeBackend.payments;

import ITubeBackend.common.client.DownstreamUnavailableException;
import ITubeBackend.common.client.PaymentsClient;
import ITubeBackend.config.PlaybackProperties;
import ITubeBackend.dto.entitlement.PlaybackDecision;
import ITubeBackend.dto.video.PlaybackAuthorisationDto;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.service.entitlement.PlaybackTokenService;
import ITubeBackend.service.video.VideoService;
import ITubeBackend.support.CatalogTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Members-only playback in catalog-service: the membership decision comes from payments-service (here a mock), the
 * playback token and the media endpoint guard are the catalog's. Membership rules themselves (tiers, expiry, grace)
 * are tested in payments-service (EntitlementServiceTest).
 */
class MembersOnlyPlaybackTest {
    private static final long CREATOR = 99L;
    private static final long VIEWER = 7L;
    private static final long CHANNEL = 5L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);

    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final PaymentsClient payments = mock(PaymentsClient.class);
    private VideoService videoService;

    @BeforeEach
    void setUp() {
        PlaybackTokenService tokens = new PlaybackTokenService(
                new PlaybackProperties(Base64.getEncoder().encodeToString(new byte[32]), Duration.ofMinutes(10), Duration.ofHours(6)), CLOCK);
        videoService = CatalogTestSupport.videoService(mock(ChannelRepository.class), videoRepository,
                mock(ApplicationEventPublisher.class), payments, tokens, null, null, null, CLOCK);
        video(1L, VideoVisibility.PUBLIC, null);
        video(2L, VideoVisibility.MEMBERS_ONLY, null);
        video(3L, VideoVisibility.MEMBERS_ONLY, 3);
        video(4L, VideoVisibility.PRIVATE, null);
        when(payments.decide(any(), any())).thenReturn(PlaybackDecision.deny("MEMBERSHIP_REQUIRED"));
    }

    @Test
    void directRequestForMembersOnlyFileWithoutTokenIsRefused() {
        // Hiding the player isn't the protection: the media endpoint itself refuses
        assertThatThrownBy(() -> videoService.assertCanStream(2L, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void publicVideosStreamWithoutATokenAndNeverAskPayments() {
        assertThat(videoService.assertCanStream(1L, null)).isTrue();
        assertThat(videoService.authorisePlayback(1L, VIEWER).allowed()).isTrue();
        verify(payments, never()).decide(any(), any());
    }

    @Test
    void nonMemberIsToldMembershipIsRequiredAndGetsNoToken() {
        PlaybackAuthorisationDto result = videoService.authorisePlayback(2L, VIEWER);
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("MEMBERSHIP_REQUIRED");
        assertThat(result.token()).isNull();
        // Signed-out viewers are answered without a call to payments
        assertThat(videoService.authorisePlayback(2L, null).reason()).isEqualTo("SIGN_IN_REQUIRED");
    }

    @Test
    void memberGetsATokenThatUnlocksOnlyThatVideo() {
        when(payments.decide(eq(VIEWER), any())).thenReturn(PlaybackDecision.allow());

        PlaybackAuthorisationDto result = videoService.authorisePlayback(2L, VIEWER);

        assertThat(result.allowed()).isTrue();
        assertThat(videoService.assertCanStream(2L, result.token())).isFalse();
        assertThatThrownBy(() -> videoService.assertCanStream(3L, result.token())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void creatorCanAlwaysWatchTheirOwnVideosWithoutAskingPayments() {
        assertThat(videoService.authorisePlayback(3L, CREATOR).allowed()).isTrue();
        assertThat(videoService.authorisePlayback(4L, CREATOR).allowed()).isTrue();
        verify(payments, never()).decide(any(), any());
    }

    @Test
    void privateVideosLookMissingToEveryoneElse() {
        assertThatThrownBy(() -> videoService.authorisePlayback(4L, VIEWER))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void paidContentFailsClosedWhenPaymentsIsDownButPublicVideosStillPlay() {
        when(payments.decide(any(), any())).thenThrow(new DownstreamUnavailableException("payments", null));

        assertThatThrownBy(() -> videoService.authorisePlayback(2L, VIEWER))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(videoService.authorisePlayback(1L, VIEWER).allowed()).isTrue();
    }

    private void video(Long id, VideoVisibility visibility, Integer level) {
        when(videoRepository.findAccess(id)).thenReturn(Optional.of(new VideoAccessDto(id, CHANNEL, CREATOR, visibility, level, LocalTime.of(0, 45))));
    }
}
