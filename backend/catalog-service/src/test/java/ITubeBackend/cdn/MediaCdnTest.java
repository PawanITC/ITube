package ITubeBackend.cdn;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

class MediaCdnTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static String pem(KeyPair keys) {
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    @Test
    void signatureCryptographicallyBindsExactObjectAndExpiry() throws Exception {
        KeyPair keys = keys();
        MediaCdn cdn = new MediaCdn("https://demo.cloudfront.net", true, "cloudfront", "KEY123",
                pem(keys), Duration.ofMinutes(5), CLOCK);
        URI url = cdn.signedUrl("videos/abc/renditions/720p.mp4", true).orElseThrow();
        Map<String, String> params = Arrays.stream(url.getRawQuery().split("&"))
                .map(p -> p.split("=", 2)).collect(Collectors.toMap(p -> p[0], p -> p[1]));
        assertThat(params.get("Expires")).isEqualTo(Long.toString(NOW.plusSeconds(300).getEpochSecond()));
        assertThat(params.get("Key-Pair-Id")).isEqualTo("KEY123");
        String policy = "{\"Statement\":[{\"Resource\":\"https://demo.cloudfront.net/videos/abc/renditions/720p.mp4\","
                + "\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":" + params.get("Expires") + "}}}]}";
        byte[] signature = Base64.getDecoder().decode(params.get("Signature")
                .replace('-', '+').replace('_', '=').replace('~', '/'));
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update(policy.getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(signature)).isTrue();
        verifier.initVerify(keys.getPublic());
        verifier.update(policy.replace("720p", "1080p").getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(signature)).isFalse();
        assertThat(cdn.publicUrl("videos/abc/source", true)).isEmpty();
        assertThat(cdn.requiresPublicTags()).isFalse();
    }

    @Test
    void invalidOrMissingSigningConfigurationFailsClosed() {
        assertThatThrownBy(() -> new MediaCdn("https://demo.cloudfront.net", true, "cloudfront", "", "",
                Duration.ofMinutes(5), CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaCdn("https://demo.cloudfront.net", true, "cloudfront", "KEY", "secret-invalid",
                Duration.ofMinutes(5), CLOCK)).hasMessage("CloudFront requires a PKCS#8 RSA-2048 private key");
        assertThatThrownBy(() -> new MediaCdn("https://demo.cloudfront.net/path", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannotSignWildcardOrTraversalAndVideoOptOutIsHonoured() throws Exception {
        MediaCdn cdn = new MediaCdn("https://demo.cloudfront.net", false, "cloudfront", "KEY123", pem(keys()),
                Duration.ofMinutes(5), CLOCK);
        assertThat(cdn.signedUrl("videos/abc/source", true)).isEmpty();
        assertThat(cdn.signedUrl("videos/abc/thumbnail-123", false)).isPresent();
        for (String key : new String[]{"videos/*", "videos/../assets/test", "assets/test", "videos/x?other=1", "videos/%2e%2e/x"}) {
            assertThatThrownBy(() -> cdn.signedUrl(key, false)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void disabledAndLegacyModesRemainCompatible() {
        assertThat(new MediaCdn(null, false).enabled()).isFalse();
        MediaCdn legacy = new MediaCdn("https://media.example.com", false);
        assertThat(legacy.publicUrl("videos/abc/thumbnail-123", false)).contains(URI.create("https://media.example.com/videos/abc/thumbnail-123"));
        assertThat(legacy.publicUrl("videos/abc/source", true)).isEmpty();
        assertThat(legacy.signedUrl("videos/abc/source", true)).isEmpty();
    }
}
