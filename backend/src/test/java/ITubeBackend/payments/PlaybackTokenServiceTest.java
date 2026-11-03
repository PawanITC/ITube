package ITubeBackend.payments;

import ITubeBackend.config.PlaybackProperties;
import ITubeBackend.service.entitlement.PlaybackTokenService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;

import static ITubeBackend.payments.StripeFixtures.CLOCK;
import static ITubeBackend.payments.StripeFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class PlaybackTokenServiceTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final PlaybackProperties properties = new PlaybackProperties(KEY, Duration.ofMinutes(10));
    private final PlaybackTokenService service = new PlaybackTokenService(properties, CLOCK);

    @Test
    void tokenIsValidForItsVideoUntilItExpires() {
        String token = service.issue(42, 7).token();
        assertThat(service.isValid(42, token)).isTrue();

        PlaybackTokenService later = new PlaybackTokenService(properties,
                Clock.fixed(NOW.plus(Duration.ofMinutes(11)), ZoneOffset.UTC));
        assertThat(later.isValid(42, token)).isFalse();
    }

    @Test
    void tokenForOneVideoDoesNotUnlockAnother() {
        assertThat(service.isValid(43, service.issue(42, 7).token())).isFalse();
    }

    @Test
    void tamperedOrMissingTokensAreRejected() {
        String token = service.issue(42, 7).token();
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString("43.7.9999999999".getBytes());
        assertThat(service.isValid(43, forgedPayload + token.substring(token.indexOf('.')))).isFalse();
        assertThat(service.isValid(42, null)).isFalse();
        assertThat(service.isValid(42, "not-a-token")).isFalse();
    }

    @Test
    void tokensSignedWithAnotherKeyAreRejected() {
        String otherKey = Base64.getEncoder().encodeToString("a-completely-different-32-byte-key".getBytes());
        PlaybackTokenService other = new PlaybackTokenService(new PlaybackProperties(otherKey, Duration.ofMinutes(10)), CLOCK);
        assertThat(service.isValid(42, other.issue(42, 7).token())).isFalse();
    }
}
