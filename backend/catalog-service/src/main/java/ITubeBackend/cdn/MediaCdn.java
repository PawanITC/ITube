package ITubeBackend.cdn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Optional;

/**
 * Media delivery: signed CloudFront URLs, or legacy public-only Cloudflare URLs.
 * <p>
 * Without a CDN every viewer gets their own pre-signed S3 URL. Those URLs are all different, so no cache can share
 * them: every view is a full download from S3 (slow far from eu-west-2, and the most expensive part of the bill).
 * With {@code itube.cdn.media-base-url} set, media that anyone may see is served from one stable URL per file,
 * {@code https://media.example.com/<key>}, which the CDN caches at hundreds of edge locations: the first viewer in a
 * city fetches it from S3, everyone after them gets it from the edge in milliseconds.
 * <p>
 * CloudFront signs every object after the caller authorizes access, including public media. The edge validates the
 * signature on cache hits too. A visibility change stops new public grants; already-issued grants last until expiry.
 * Legacy Cloudflare mode only serves public objects; restricted media retains pre-signed S3 URLs.
 * <p>
 * {@code itube.cdn.serve-video}: thumbnails always go through the CDN when it's configured; video files only when this
 * is true. Cloudflare's terms only allow serving video through its CDN with a paid video product or with R2
 * storage, so check your plan before turning this on (docs/CDN-AND-DNS.md).
 */
@Component
public class MediaCdn {
    private final String baseUrl;
    private final boolean serveVideo;
    private final String provider;
    private final String keyId;
    private final RSAPrivateKey privateKey;
    private final Duration ttl;
    private final Clock clock;

    public MediaCdn(String baseUrl, boolean serveVideo) {
        this(baseUrl, serveVideo, "cloudflare", "", "", Duration.ofMinutes(5), Clock.systemUTC());
    }

    @Autowired
    public MediaCdn(@Value("${itube.cdn.media-base-url:}") String baseUrl,
                    @Value("${itube.cdn.serve-video:false}") boolean serveVideo,
                    @Value("${itube.cdn.provider:cloudflare}") String provider,
                    @Value("${itube.cdn.key-id:}") String keyId,
                    @Value("${itube.cdn.private-key:}") String privateKeyPem,
                    @Value("${itube.cdn.url-ttl:5m}") Duration ttl, Clock clock) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.replaceAll("/+$", "");
        this.serveVideo = serveVideo;
        this.provider = provider;
        this.keyId = keyId;
        this.ttl = ttl;
        this.clock = clock;
        if (!provider.equals("cloudflare") && !provider.equals("cloudfront")) {
            throw new IllegalArgumentException("CDN provider must be cloudflare or cloudfront");
        }
        if (this.baseUrl != null) {
            URI base = URI.create(this.baseUrl);
            if (!"https".equals(base.getScheme()) || base.getHost() == null || base.getRawUserInfo() != null
                    || base.getRawQuery() != null || base.getRawFragment() != null || !base.getRawPath().isEmpty()) {
                throw new IllegalArgumentException("CDN base URL must be an HTTPS origin without a path/query");
            }
        }
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(30)) < 0 || ttl.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("CDN URL TTL must be between 30 seconds and 15 minutes");
        }
        privateKey = signed() ? parseKey(privateKeyPem, keyId) : null;
    }

    private static RSAPrivateKey parseKey(String pem, String keyId) {
        if (keyId == null || !keyId.matches("[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("CloudFront signing key ID is required");
        }
        try {
            String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            RSAPrivateKey key = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            if (key.getModulus().bitLength() != 2048) throw new IllegalArgumentException();
            return key;
        } catch (Exception ignored) {
            throw new IllegalArgumentException("CloudFront requires a PKCS#8 RSA-2048 private key");
        }
    }

    public boolean signed() { return enabled() && provider.equals("cloudfront"); }
    public boolean requiresPublicTags() { return enabled() && !signed(); }

    /** Call after authorization. Even public media is signed to bound access after a visibility change. */
    public Optional<URI> signedUrl(String key, boolean isVideo) {
        if (!signed() || (isVideo && !serveVideo)) return Optional.empty();
        String url = objectUrl(key);
        return Optional.of(URI.create(CloudFrontUtilities.create().getSignedUrlWithCannedPolicy(r -> r
                .resourceUrl(url).privateKey(privateKey).keyPairId(keyId)
                .expirationDate(clock.instant().plus(ttl))).url()));
    }

    private String objectUrl(String key) {
        if (key == null || !key.matches("videos/[A-Za-z0-9_/-]+(?:\\.[A-Za-z0-9]+)?")
                || key.contains("//") || key.contains("..")) {
            throw new IllegalArgumentException("Invalid media object key");
        }
        return baseUrl + "/" + key;
    }

    public boolean enabled() {
        return baseUrl != null;
    }

    /** The CDN URL for a public object, if this kind of media is served through the CDN. */
    public Optional<URI> publicUrl(String key, boolean isVideo) {
        if (baseUrl == null || signed() || (isVideo && !serveVideo)) return Optional.empty();
        return Optional.of(URI.create(objectUrl(key)));
    }
}
