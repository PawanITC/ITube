package ITubeBackend;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.client.DownstreamUnavailableException;
import ITubeBackend.common.client.RecommendationCatalogClient;
import ITubeBackend.controller.AutomaticPlaylistController;
import ITubeBackend.dto.video.RecommendationCandidate;
import ITubeBackend.dto.video.RecommendationCandidateRequest;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.engagement.memory.InMemoryAutomaticPlaylistStore;
import ITubeBackend.engagement.memory.InMemoryEngagementStores;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.service.playlists.AutomaticPlaylistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ITubeBackend.security.AccessTokens;
import ITubeBackend.security.AuthProperties;
import ITubeBackend.security.AuthenticationConfig;
import ITubeBackend.security.SignedInUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AutomaticPlaylistTests {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private InMemoryEngagementStores.History history;
    private InMemoryEngagementStores.Likes likes;
    private CatalogClient catalog;
    private RecommendationCatalogClient recommendations;
    private AutomaticPlaylistService service;
    private Map<Long, VideoSummaryDto> metadata;
    private List<RecommendationCandidate> candidates;
    private MockMvc mvc;
    private RequestPostProcessor viewer;

    @BeforeEach
    void setup() {
        history = new InMemoryEngagementStores.History();
        likes = new InMemoryEngagementStores.Likes();
        catalog = mock(CatalogClient.class);
        recommendations = mock(RecommendationCatalogClient.class);
        metadata = new HashMap<>();
        candidates = new ArrayList<>();
        when(catalog.visibleVideoSummaries(anyCollection(), anyLong())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return ids.stream().filter(metadata::containsKey).collect(Collectors.toMap(id -> id, metadata::get));
        });
        when(recommendations.candidates(any())).thenAnswer(call -> List.copyOf(candidates));
        when(recommendations.eligibleVideos(anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            return ids.stream().filter(metadata::containsKey).map(metadata::get).toList();
        });
        service = new AutomaticPlaylistService(history, likes, catalog, recommendations,
                new InMemoryAutomaticPlaylistStore(clock), clock);
        AccessTokens tokens = new AccessTokens(new AuthProperties(), clock);
        mvc = MockMvcBuilders.standaloneSetup(new AutomaticPlaylistController(service, mock(ITubeBackend.engagement.commands.CommandGateway.class)))
                .addFilters(new AuthenticationConfig.AuthenticationFilter(tokens)).build();
        String token = tokens.issue(new SignedInUser(1L, null, null));
        viewer = request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    @Test
    void ranksLikesAndHistoryPreferencesWithoutDuplicatingOrRepeatingSeeds() {
        video(1, 10, "Spring Boot Java", VideoVisibility.PUBLIC, "READY");
        video(2, 20, "Python basics", VideoVisibility.PUBLIC, "READY");
        candidate(3, 10, "Spring Boot advanced", 2);
        candidate(4, 20, "Python advanced", 1);
        candidate(5, 30, "Football highlights", 0);
        candidates.add(candidates.getFirst());
        candidates.add(new RecommendationCandidate(metadata.get(1L), NOW));
        likes.likeIfAbsent(1, 1, NOW);
        history.add(1, 2, NOW);
        var mix = service.current(1L, false);
        assertThat(mix.personalized()).isTrue();
        assertThat(mix.videos()).extracting(row -> row.videoId()).containsExactly(3L, 4L, 5L);
        verify(recommendations).candidates(argThat(request -> request.preferredChannelIds().getFirst() == 10L
                && new HashSet<>(request.excludedVideoIds()).equals(Set.of(1L, 2L))));
    }

    @Test
    void coldStartUsesFreshPublicReadyVideosAndCapsTheMixAtThirty() {
        for (long id = 1; id <= 45; id++) candidate(id, id, "Discovery " + id, (int) (45 - id));
        var mix = service.current(1L, false);
        assertThat(mix.personalized()).isFalse();
        assertThat(mix.videos()).hasSize(30);
        assertThat(mix.videos().getFirst().videoId()).isEqualTo(45L);
        assertThat(mix.videos()).extracting(row -> row.videoId()).doesNotHaveDuplicates();
        assertThat(mix.videos()).extracting(row -> row.position()).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, 30).boxed().toList());
    }

    @Test
    void neverReturnsPrivateUnlistedMembersOnlyOrUnprocessedCandidates() {
        candidate(1, 10, "Public", 0);
        for (var visibility : List.of(VideoVisibility.PRIVATE, VideoVisibility.UNLISTED, VideoVisibility.MEMBERS_ONLY)) {
            long id = visibility.ordinal() + 20L;
            candidates.add(new RecommendationCandidate(video(id, id, "Restricted", visibility, "READY"), NOW));
        }
        candidates.add(new RecommendationCandidate(video(99, 99, "Not ready", VideoVisibility.PUBLIC, "PROCESSING"), NOW));
        assertThat(service.current(1L, false).videos()).extracting(row -> row.videoId()).containsExactly(1L);
    }

    @Test
    void watchLikeUnlikeAndHistoryClearChangeTheNextMixButKeepExistingPlaybackOrder() {
        candidate(1, 10, "Java", 0);
        candidate(2, 20, "Python", 1);
        var original = service.current(1L, false);
        history.add(1, 1, NOW);
        var afterWatch = service.current(1L, false);
        assertThat(afterWatch.playlist().id()).isNotEqualTo(original.playlist().id());
        assertThat(afterWatch.videos()).extracting(row -> row.videoId()).containsExactly(2L);
        likes.likeIfAbsent(1, 2, NOW);
        assertThat(service.current(1L, false).videos()).isEmpty();
        likes.unlike(1, 2);
        assertThat(service.current(1L, false).videos()).hasSize(1);
        history.clear(1);
        var reset = service.current(1L, false);
        assertThat(reset.personalized()).isFalse();
        assertThat(reset.videos()).hasSize(2);
        assertThat(service.snapshot(1L, original.playlist().id()).videos()).extracting(row -> row.videoId()).containsExactly(1L, 2L);
    }

    @Test
    void shortCacheAvoidsRerankingButVisibilityIsAlwaysCheckedAgain() {
        candidate(1, 10, "Public now", 0);
        candidate(2, 20, "Deleted later", 1);
        var first = service.current(1L, false);
        video(1, 10, "Now private", VideoVisibility.PRIVATE, "READY");
        metadata.remove(2L);
        assertThat(service.current(1L, false).videos()).isEmpty();
        assertThat(service.snapshot(1L, first.playlist().id()).videos()).isEmpty();
        verify(recommendations, times(1)).candidates(any());
        verify(recommendations, times(3)).eligibleVideos(anyList());
    }

    @Test
    void explicitRefreshGetsNewCatalogCandidatesWithoutChangingManualPlaylists() {
        candidate(1, 10, "First", 0);
        var first = service.current(1L, false);
        candidate(2, 20, "New upload", -1);
        var refreshed = service.current(1L, true);
        assertThat(refreshed.playlist().id()).isNotEqualTo(first.playlist().id());
        assertThat(refreshed.videos().getFirst().videoId()).isEqualTo(2L);
        verify(recommendations, times(2)).candidates(any());
    }

    @Test
    void snapshotsAreOwnerScopedAndTheApiRequiresAuthentication() throws Exception {
        candidate(1, 10, "Public", 0);
        var mix = service.current(1L, false);
        assertThatThrownBy(() -> service.snapshot(2L, mix.playlist().id())).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        mvc.perform(get("/api/playlists/automatic")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/playlists/automatic/refresh")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/playlists/automatic/" + mix.playlist().id())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/playlists/automatic").with(viewer)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.playlist.title").value("For you"));
        mvc.perform(get("/api/playlists/automatic/not-a-uuid").with(viewer)).andExpect(status().isBadRequest());
    }

    @Test
    void catalogFailureReturns503InsteadOfServingPotentiallyPrivateStaleMetadata() throws Exception {
        candidate(1, 10, "Public", 0);
        service.current(1L, false);
        when(recommendations.eligibleVideos(anyList())).thenThrow(new DownstreamUnavailableException("catalog", null));
        mvc.perform(get("/api/playlists/automatic").with(viewer)).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
    }

    @Test
    void inferredTopicsAndTypicalDurationHelpDiscoverVideosOnNewChannels() {
        video(1, 10, "Java Spring Boot", VideoVisibility.PUBLIC, "READY");
        likes.likeIfAbsent(1, 1, NOW);
        candidate(2, 20, "Spring Boot deployment", 0);
        candidate(3, 30, "Football", 0);
        assertThat(service.current(1L, false).videos().getFirst().videoId()).isEqualTo(2L);

        candidates.clear();
        candidate(4, 40, "A new subject", 0);
        var longVideo = new VideoSummaryDto(5L, 40L, "A new subject", "Channel 40", LocalTime.of(1, 0),
                true, "READY", VideoVisibility.PUBLIC, null);
        metadata.put(5L, longVideo);
        candidates.add(new RecommendationCandidate(longVideo, NOW));
        assertThat(service.current(1L, true).videos().getFirst().videoId()).isEqualTo(4L);
    }

    @Test
    void anOldLikeHasLessInfluenceThanARecentWatch() {
        video(1, 10, "Java", VideoVisibility.PUBLIC, "READY");
        video(2, 20, "Python", VideoVisibility.PUBLIC, "READY");
        likes.likeIfAbsent(1, 1, NOW.minus(Duration.ofDays(730)));
        history.add(1, 2, NOW);
        candidate(3, 10, "Java advanced", 0);
        candidate(4, 20, "Python advanced", 0);
        assertThat(service.current(1L, false).videos().getFirst().videoId()).isEqualTo(4L);
    }

    @Test
    void signalReadsAreBoundedAndRepeatedPlaybackCannotOutweighExplicitLikes() {
        video(1, 10, "Java", VideoVisibility.PUBLIC, "READY");
        video(2, 20, "Python", VideoVisibility.PUBLIC, "READY");
        likes.likeIfAbsent(1, 1, NOW);
        for (int index = 0; index < 500; index++) history.add(1, 2, NOW);
        candidate(3, 10, "Java advanced", 1);
        candidate(4, 20, "Python advanced", 0);
        assertThat(service.current(1L, false).videos().getFirst().videoId()).isEqualTo(3L);
        verify(recommendations).candidates(argThat(request -> request.limit() == 300 && request.excludedVideoIds().size() <= 300));
    }

    private VideoSummaryDto video(long id, long channel, String title, VideoVisibility visibility, String status) {
        var video = new VideoSummaryDto(id, channel, title, "Channel " + channel, LocalTime.of(0, 10), true, status, visibility, null);
        metadata.put(id, video);
        return video;
    }

    private void candidate(long id, long channel, String title, int daysAgo) {
        candidates.add(new RecommendationCandidate(video(id, channel, title, VideoVisibility.PUBLIC, "READY"), NOW.minus(Duration.ofDays(daysAgo))));
    }
}
