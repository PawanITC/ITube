package ITubeBackend.support;

import ITubeBackend.cache.CatalogCaches;
import ITubeBackend.cdn.MediaCdn;
import ITubeBackend.common.cache.CacheConfig;
import ITubeBackend.common.client.PaymentsClient;
import ITubeBackend.common.events.ItubeTopics;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.service.entitlement.PlaybackTokenService;
import ITubeBackend.service.video.VideoService;
import ITubeBackend.sharding.IdAllocator;
import ITubeBackend.sharding.OwnerDirectory;
import ITubeBackend.sharding.ShardMap;
import ITubeBackend.sharding.Shards;
import ITubeBackend.sharding.ShardingProperties;
import ITubeBackend.storage.ObjectStorage;
import ITubeBackend.storage.StorageProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds catalog services for plain unit tests (mocked repositories, no database): one shard, every creator on
 * logical shard 0, new video ids from {@link #NEXT_VIDEO_ID}. Integration tests with real (H2) shards use the
 * Spring context instead (profile h2: two databases).
 */
public final class CatalogTestSupport {
    public static final long NEXT_VIDEO_ID = 42L;

    private CatalogTestSupport() {
    }

    public static Shards singleShard() {
        ShardingProperties properties = new ShardingProperties(
                List.of(new ShardingProperties.Shard("jdbc:unused", null, null, null)), Duration.ofSeconds(2), false, 1);
        OwnerDirectory directory = mock(OwnerDirectory.class);
        when(directory.find(anyLong())).thenReturn(OptionalInt.of(0));
        when(directory.assign(anyLong())).thenReturn(0);
        return new Shards(new ShardMap(properties), directory, properties, new SimpleMeterRegistry());
    }

    public static IdAllocator idAllocator() {
        IdAllocator ids = mock(IdAllocator.class);
        when(ids.nextVideoId(anyInt())).thenReturn(NEXT_VIDEO_ID);
        when(ids.nextChannelId(anyInt())).thenReturn(5L);
        return ids;
    }

    @SuppressWarnings("unchecked")
    public static CatalogCaches caches() {
        CacheManager cacheManager = new CacheConfig().cacheManager(new CacheConfig.CacheSpecs(Map.of(
                "video-access", "maximumSize=100", "video-media", "maximumSize=100", "video-row", "maximumSize=100",
                "feed", "maximumSize=10", "channel", "maximumSize=100")));
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        ItubeTopics topics = new ItubeTopics("video.transcode", "video.transcoded", "video.deleted", "account.created",
                "catalog.cache-invalidation", "a", "b", "c", 1, 1);
        return new CatalogCaches(cacheManager, kafka, topics);
    }

    public static StorageProperties storageProperties(boolean redirect) {
        return new StorageProperties("local", null, null, null, null, null, null,
                false, false, null, Duration.ofHours(1), redirect, null);
    }

    public static VideoService videoService(ChannelRepository channels, VideoRepository videos,
                                            ApplicationEventPublisher events, PaymentsClient payments,
                                            PlaybackTokenService tokens, ObjectStorage storage,
                                            StorageProperties storageProperties, TransactionTemplate transactions,
                                            Clock clock) {
        return new VideoService(channels, videos, events, payments, tokens, storage, storageProperties, transactions,
                singleShard(), idAllocator(), caches(), new MediaCdn(null, false), clock);
    }

    /** For tests that don't care about payments: every members-only request is refused. */
    public static PaymentsClient denyAllPayments() {
        PaymentsClient payments = mock(PaymentsClient.class);
        when(payments.decide(any(), any())).thenReturn(ITubeBackend.dto.entitlement.PlaybackDecision.deny("MEMBERSHIP_REQUIRED"));
        return payments;
    }
}
