package ITubeBackend.cdn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Optional;

/**
 * Public media through the CDN (Cloudflare, see deploy/cloudflare).
 * <p>
 * Without a CDN every viewer gets their own pre-signed S3 URL. Those URLs are all different, so no cache can share
 * them: every view is a full download from S3 (slow far from eu-west-2, and the most expensive part of the bill).
 * With {@code itube.cdn.media-base-url} set, media that anyone may see is served from one stable URL per file,
 * {@code https://media.example.com/<key>}, which the CDN caches at hundreds of edge locations: the first viewer in a
 * city fetches it from S3, everyone after them gets it from the edge in milliseconds.
 * <p>
 * Members-only and private media never use the CDN: they keep short-lived pre-signed URLs, and the bucket policy only
 * lets the CDN read objects tagged {@code public=true}.
 * <p>
 * {@code itube.cdn.serve-video}: thumbnails always go through the CDN when it's configured; video files only when this
 * is true. Cloudflare's terms only allow serving video through its CDN with a paid video product or with R2
 * storage, so check your plan before turning this on (docs/CDN-AND-DNS.md).
 */
@Component
public class MediaCdn {
    private final String baseUrl;
    private final boolean serveVideo;

    public MediaCdn(@Value("${itube.cdn.media-base-url:}") String baseUrl,
                    @Value("${itube.cdn.serve-video:false}") boolean serveVideo) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.replaceAll("/+$", "");
        this.serveVideo = serveVideo;
    }

    public boolean enabled() {
        return baseUrl != null;
    }

    /** The CDN URL for a public object, if this kind of media is served through the CDN. */
    public Optional<URI> publicUrl(String key, boolean isVideo) {
        if (baseUrl == null || (isVideo && !serveVideo)) return Optional.empty();
        return Optional.of(URI.create(baseUrl + "/" + key));
    }
}
