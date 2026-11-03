package ITubeBackend.service.entitlement;

import ITubeBackend.config.PlaybackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * Issues and checks short-lived, HMAC-signed playback tokens bound to one video and one user.
 * This plays the role CDN signed URLs/cookies will play once media moves to object storage:
 * only the backend can mint them, and only after checking entitlement.
 */
@Component
public class PlaybackTokenService {
    private static final Logger log = LoggerFactory.getLogger(PlaybackTokenService.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final PlaybackProperties properties;
    private final Clock clock;

    public record IssuedToken(String token, OffsetDateTime expiresAt) {
    }

    public PlaybackTokenService(PlaybackProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        byte[] secret;
        if (properties.signingKey() == null || properties.signingKey().isBlank()) {
            log.warn("PLAYBACK_SIGNING_KEY is not set; using a random key (tokens won't survive a restart or work across instances)");
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
        } else {
            secret = Base64.getDecoder().decode(properties.signingKey().trim());
            if (secret.length < 32) throw new IllegalStateException("PLAYBACK_SIGNING_KEY must be at least 32 bytes (base64)");
        }
        this.key = new SecretKeySpec(secret, ALGORITHM);
    }

    /**
     * The video is streamed in many range requests, each checking the token, so the token must
     * outlast playback: base TTL plus the video's length, capped so a leaked token can't live forever.
     */
    public IssuedToken issue(long videoId, long userId, Duration videoLength) {
        Duration ttl = properties.tokenTtl().plus(videoLength == null ? Duration.ZERO : videoLength);
        if (ttl.compareTo(properties.maxTokenTtl()) > 0) ttl = properties.maxTokenTtl();
        long expires = Instant.now(clock).plus(ttl).getEpochSecond();
        String payload = videoId + "." + userId + "." + expires;
        String token = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + ENCODER.encodeToString(sign(payload));
        return new IssuedToken(token, OffsetDateTime.ofInstant(Instant.ofEpochSecond(expires), ZoneOffset.UTC));
    }

    public boolean isValid(long videoId, String token) {
        if (token == null) return false;
        String[] parts = token.split("\\.");
        if (parts.length != 2) return false;
        try {
            String payload = new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(sign(payload), DECODER.decode(parts[1]))) return false;
            String[] fields = payload.split("\\.");
            return fields.length == 3
                    && Long.parseLong(fields[0]) == videoId
                    && Long.parseLong(fields[2]) > Instant.now(clock).getEpochSecond();
        } catch (IllegalArgumentException exception) {
            return false; // bad base64 or number
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }
}
