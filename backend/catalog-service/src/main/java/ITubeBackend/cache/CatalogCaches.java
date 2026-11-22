package ITubeBackend.cache;

import ITubeBackend.common.cache.CacheConfig;
import ITubeBackend.common.events.ItubeTopics;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.dto.video.AdCandidate;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoMediaDto;
import ITubeBackend.dto.video.VideoRow;
import ITubeBackend.dto.video.VideoSummaryDto;
import com.github.benmanes.caffeine.cache.Cache;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The catalog's Caffeine caches (sizes/TTLs: itube.cache.specs.* in application.properties):
 * <ul>
 *   <li>video-access / video-media / video-row: per video, read on every play, thumbnail and History page;</li>
 *   <li>feed: the home page, rebuilt from all shards at most every 10 s per pod instead of on every visit;</li>
 *   <li>channel: per channel, for other services;</li>
 *   <li>video-ads: per video, how many ads play in it (read on every watch page);</li>
 *   <li>ad-pool: the public ads to choose from, gathered from all shards at most every 30 s per pod.</li>
 * </ul>
 * <b>Keeping several pods consistent:</b> each pod has its own caches. When a video changes, this pod evicts it and
 * broadcasts the id on Kafka (catalog.cache-invalidation); every catalog pod listens with its own consumer group,
 * so all of them evict it within milliseconds. If Kafka is down the TTLs are the safety net (at most 30-60 s stale).
 */
@Component
public class CatalogCaches {
    private static final Logger log = LoggerFactory.getLogger(CatalogCaches.class);

    public final Cache<Long, VideoAccessDto> videoAccess;
    public final Cache<Long, VideoMediaDto> videoMedia;
    public final Cache<Long, VideoRow> videoRows;
    public final Cache<Integer, List<VideoSummaryDto>> feed;
    public final Cache<Long, ChannelDto> channels;
    public final Cache<Long, Integer> videoAds;
    /** One entry (key 0): ads are picked from this list instead of asking every shard on every view. */
    public final Cache<Integer, List<AdCandidate>> adPool;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ItubeTopics topics;

    public CatalogCaches(CacheManager cacheManager, KafkaTemplate<String, String> kafkaTemplate, ItubeTopics topics) {
        this.videoAccess = CacheConfig.nativeCache(cacheManager, "video-access");
        this.videoMedia = CacheConfig.nativeCache(cacheManager, "video-media");
        this.videoRows = CacheConfig.nativeCache(cacheManager, "video-row");
        this.feed = CacheConfig.nativeCache(cacheManager, "feed");
        this.channels = CacheConfig.nativeCache(cacheManager, "channel");
        this.videoAds = CacheConfig.nativeCache(cacheManager, "video-ads");
        this.adPool = CacheConfig.nativeCache(cacheManager, "ad-pool");
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
    }

    /** Call after a video's change has committed: evicts it here and on every other catalog pod. */
    public void videoChanged(Long videoId) {
        evictLocally(videoId);
        try {
            kafkaTemplate.send(topics.catalogCacheInvalidation(), videoId.toString(), videoId.toString())
                    .whenComplete((result, error) -> {
                        if (error != null) log.debug("Cache invalidation not broadcast for video {}: {}", videoId, error.toString());
                    });
        } catch (RuntimeException exception) {
            // Other pods fall back to their TTLs
            log.debug("Cache invalidation not broadcast for video {}: {}", videoId, exception.toString());
        }
    }

    /** Every pod gets every invalidation: a unique group id per pod, starting from "now" (old ones don't matter). */
    @KafkaListener(topics = "${itube.topics.catalog-cache-invalidation}",
            groupId = "catalog-cache-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=latest")
    void onInvalidation(String videoId) {
        try {
            evictLocally(Long.valueOf(videoId.trim()));
        } catch (NumberFormatException ignored) {
            // nothing sensible to evict
        }
    }

    private void evictLocally(Long videoId) {
        videoAccess.invalidate(videoId);
        videoMedia.invalidate(videoId);
        videoRows.invalidate(videoId);
        videoAds.invalidate(videoId);
        // Visibility or deletion changes what the feed may show and which ads may play; both are cheap to rebuild
        feed.invalidateAll();
        adPool.invalidateAll();
    }

    @Configuration
    static class Topics {
        @Bean
        NewTopic catalogCacheInvalidationTopic(ItubeTopics topics) {
            return topics.declare(topics.catalogCacheInvalidation());
        }
    }
}
