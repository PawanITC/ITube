package ITubeBackend;

import ITubeBackend.common.client.PaymentsClient;
import ITubeBackend.dto.video.RecommendationCandidateRequest;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.entities.VideoEntity;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.entities.VideoType;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.service.video.RecommendationCatalogService;
import ITubeBackend.sharding.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs against two H2 shards normally; optional URLs let the same test verify real PostgreSQL. */
@SpringBootTest
@ActiveProfiles("h2")
class RecommendationCatalogTests {
    @Autowired RecommendationCatalogService service;
    @Autowired ChannelRepository channels;
    @Autowired VideoRepository videos;
    @Autowired IdAllocator ids;
    @Autowired ShardMap shardMap;
    @Autowired TransactionTemplate transactions;
    @MockitoBean PaymentsClient payments;
    private record Fixture(int physical, long channelId, long videoId) { }
    private final List<Fixture> created = new ArrayList<>();
    private static final AtomicLong OWNERS = new AtomicLong(900_000);

    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry properties) {
        for (int shard = 0; shard < 2; shard++) {
            String url = System.getProperty("itube.test.pg.shard" + shard);
            if (url != null) {
                String prefix = "itube.sharding.shards[" + shard + "].";
                properties.add(prefix + "url", () -> url);
                properties.add(prefix + "username", () -> "itube");
                properties.add(prefix + "password", () -> System.getenv().getOrDefault("ITUBE_TEST_PG_PASSWORD", "itube-dev-password"));
            }
        }
    }

    @AfterEach
    void cleanup() {
        for (var fixture : created) ShardContext.on(fixture.physical(), () -> transactions.execute(status -> {
            videos.deleteById(fixture.videoId());
            videos.flush();
            channels.deleteById(fixture.channelId());
            channels.flush();
            return null;
        }));
    }

    @Test
    void candidatesAcrossShardsExcludeSeenPrivateUnlistedMembersOnlyAndUnavailableRows() {
        var public0 = fixture(0, VideoVisibility.PUBLIC, "READY", "ready0.mp4");
        var public1 = fixture(1, VideoVisibility.PUBLIC, "READY", "ready1.mp4");
        var hidden = fixture(0, VideoVisibility.PRIVATE, "READY", "private.mp4");
        var unlisted = fixture(1, VideoVisibility.UNLISTED, "READY", "unlisted.mp4");
        var members = fixture(0, VideoVisibility.MEMBERS_ONLY, "READY", "members.mp4");
        var pending = fixture(1, VideoVisibility.PUBLIC, "PROCESSING", "pending.mp4");
        var missing = fixture(0, VideoVisibility.PUBLIC, "READY", null);
        var blank = fixture(1, VideoVisibility.PUBLIC, "READY", "  ");
        var pool = service.candidates(new RecommendationCandidateRequest(List.of(public0.channelId()), List.of(public1.videoId()), 100));
        assertThat(pool).extracting(candidate -> candidate.video().videoId()).contains(public0.videoId())
                .doesNotContain(public1.videoId(), hidden.videoId(), unlisted.videoId(), members.videoId(), pending.videoId(), missing.videoId(), blank.videoId());
        assertThat(pool).allSatisfy(candidate -> {
            assertThat(candidate.video().visibility()).isEqualTo("PUBLIC");
            assertThat(candidate.video().processingStatus()).isEqualTo("READY");
        });
        assertThat(service.eligibleVideos(List.of(public0.videoId(), public1.videoId(), hidden.videoId(), pending.videoId(), missing.videoId(), blank.videoId())))
                .extracting(video -> video.videoId()).containsExactlyInAnyOrder(public0.videoId(), public1.videoId());
    }

    @Test
    void preferredChannelsHaveReservedCandidateSpaceAndThePoolStaysBounded() {
        var preferred = fixture(0, VideoVisibility.PUBLIC, "READY", "preferred.mp4");
        fixture(1, VideoVisibility.PUBLIC, "READY", "fresh.mp4");
        var pool = service.candidates(new RecommendationCandidateRequest(List.of(preferred.channelId()), List.of(), 2));
        assertThat(pool).hasSizeLessThanOrEqualTo(2);
        assertThat(pool).extracting(candidate -> candidate.video().videoId()).contains(preferred.videoId()).doesNotHaveDuplicates();
    }

    @Test
    void advertisingVideosAreExcludedFromDiscoveryAndPlaybackSnapshots() {
        var ad = fixture(0, VideoVisibility.PUBLIC, "READY", "ad-source.mp4");
        ShardContext.on(ad.physical(), () -> transactions.execute(status -> {
            videos.findById(ad.videoId()).orElseThrow().setVideoType(VideoType.AD);
            videos.flush();
            return null;
        }));
        assertThat(service.candidates(new RecommendationCandidateRequest(List.of(ad.channelId()), List.of(), 100)))
                .extracting(candidate -> candidate.video().videoId()).doesNotContain(ad.videoId());
        assertThat(service.eligibleVideos(List.of(ad.videoId()))).isEmpty();
    }

    @Test
    void snapshotValidationSeesVisibilityChangesWithoutWaitingForCatalogCaches() {
        var video = fixture(1, VideoVisibility.PUBLIC, "READY", "source.mp4");
        assertThat(service.eligibleVideos(List.of(video.videoId()))).hasSize(1);
        ShardContext.on(video.physical(), () -> transactions.execute(status -> {
            videos.findById(video.videoId()).orElseThrow().setVisibility(VideoVisibility.PRIVATE);
            videos.flush();
            return null;
        }));
        assertThat(service.eligibleVideos(List.of(video.videoId()))).isEmpty();
    }

    private Fixture fixture(int physical, VideoVisibility visibility, String processing, String key) {
        int logical = java.util.stream.IntStream.range(0, ShardIds.LOGICAL_SHARDS)
                .filter(value -> shardMap.physicalOf(value) == physical).findFirst().orElseThrow();
        Fixture result = ShardContext.on(physical, () -> transactions.execute(status -> {
            var channel = new ChannelEntity(ids.nextChannelId(logical));
            channel.setOwnerUserId(OWNERS.incrementAndGet());
            channel.setChannelName("Recommendation test");
            channels.saveAndFlush(channel);
            var video = new VideoEntity(ids.nextVideoId(logical));
            video.setChannel(channel);
            video.setTitle("Recommendation metadata");
            video.setDuration(LocalTime.of(0, 10));
            video.setVideoContentType("video/mp4");
            video.setVideoKey(key);
            video.setVisibility(visibility);
            video.setRequiredTierLevel(visibility == VideoVisibility.MEMBERS_ONLY ? 1 : null);
            video.setProcessingStatus(processing);
            video.setCreatedAt(OffsetDateTime.now());
            videos.saveAndFlush(video);
            return new Fixture(physical, channel.getChannelId(), video.getVideoId());
        }));
        created.add(result);
        return result;
    }
}
