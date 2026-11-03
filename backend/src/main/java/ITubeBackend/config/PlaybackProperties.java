package ITubeBackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * signingKey is base64 (32+ bytes) from PLAYBACK_SIGNING_KEY; never log it.
 * A token lasts tokenTtl plus the video's length, capped at maxTokenTtl.
 */
@ConfigurationProperties(prefix = "itube.playback")
public record PlaybackProperties(String signingKey, Duration tokenTtl, Duration maxTokenTtl) {
    @Override
    public String toString() {
        return "PlaybackProperties[tokenTtl=" + tokenTtl + ", maxTokenTtl=" + maxTokenTtl + "]";
    }
}
