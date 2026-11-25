package ITubeBackend.service.playlists;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.client.RecommendationCatalogClient;
import ITubeBackend.dto.playlists.*;
import ITubeBackend.dto.video.RecommendationCandidateRequest;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.engagement.AutomaticPlaylistStore;
import ITubeBackend.engagement.AutomaticPlaylistStore.Snapshot;
import ITubeBackend.engagement.LikeStore;
import ITubeBackend.engagement.WatchHistoryStore;
import ITubeBackend.service.playlists.RecommendationRanker.Seed;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AutomaticPlaylistService {
    private static final int HISTORY_LIMIT = 200, LIKE_LIMIT = 100, CANDIDATE_LIMIT = 300, MIX_SIZE = 30;
    private record Signals(long userId, List<WatchHistoryStore.HistoryEntry> history, List<LikeStore.Like> likes) { }
    private final WatchHistoryStore history;
    private final LikeStore likes;
    private final CatalogClient catalog;
    private final RecommendationCatalogClient recommendations;
    private final AutomaticPlaylistStore snapshots;
    private final Clock clock;
    private final RecommendationRanker ranker = new RecommendationRanker();
    // Engagement is part of the key: watching, liking, unliking or clearing history changes the next mix.
    private final Cache<Signals, Snapshot> current = Caffeine.newBuilder().maximumSize(5_000)
            .expireAfterWrite(Duration.ofSeconds(60)).recordStats().build();

    public AutomaticPlaylistService(WatchHistoryStore history, LikeStore likes, CatalogClient catalog,
                                    RecommendationCatalogClient recommendations, AutomaticPlaylistStore snapshots, Clock clock) {
        this.history = history;
        this.likes = likes;
        this.catalog = catalog;
        this.recommendations = recommendations;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    public AutomaticPlaylistResponse current(Long userId, boolean refresh) {
        var signals = signals(userId);
        if (refresh) current.invalidate(signals);
        Snapshot snapshot = current.get(signals, this::generate);
        return resolve(snapshot);
    }

    private Signals signals(Long userId) {
        requireUser(userId);
        List<LikeStore.Like> recentLikes;
        try (var stream = likes.newestFirst(userId)) { recentLikes = stream.limit(LIKE_LIMIT).toList(); }
        return new Signals(userId, List.copyOf(history.page(userId, 0, HISTORY_LIMIT)), recentLikes);
    }

    /** Kafka refresh plans freeze recommendation order before persistence; retrying cannot create a new mix. */
    public Snapshot prepareRefresh(Long userId, UUID operationId) {
        return generate(signals(userId), operationId, clock.instant());
    }

    public void applyRefresh(Snapshot snapshot) {
        snapshots.save(snapshot);
        current.put(signals(snapshot.userId()), snapshot);
    }

    public AutomaticPlaylistResponse preparedResponse(Snapshot snapshot) {
        return resolve(snapshot);
    }

    public AutomaticPlaylistResponse snapshot(Long userId, UUID id) {
        requireUser(userId);
        Snapshot snapshot = snapshots.find(userId, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "This automatic playlist has expired or is unavailable. Open Playlists for a new mix."));
        return resolve(snapshot);
    }

    private Snapshot generate(Signals signals) {
        Snapshot snapshot = generate(signals, UUID.randomUUID(), clock.instant());
        snapshots.save(snapshot);
        return snapshot;
    }

    private Snapshot generate(Signals signals, UUID id, Instant now) {
        Set<Long> excluded = new LinkedHashSet<>();
        Map<Long, Double> weights = new HashMap<>();
        Map<Long, Integer> repeatCounts = new HashMap<>();
        for (var entry : signals.history()) {
            excluded.add(entry.videoId());
            if (repeatCounts.merge(entry.videoId(), 1, Integer::sum) <= 3) {
                weights.merge(entry.videoId(), decay(entry.watchedAt(), now, 30), Double::sum);
            }
        }
        for (var like : signals.likes()) {
            excluded.add(like.videoId());
            weights.merge(like.videoId(), 4 * decay(like.likedAt(), now, 180), Double::sum);
        }
        Map<Long, VideoSummaryDto> seedMetadata = excluded.isEmpty() ? Map.of() : catalog.visibleVideoSummaries(excluded, signals.userId());
        var seeds = weights.entrySet().stream().filter(entry -> seedMetadata.containsKey(entry.getKey()))
                .map(entry -> new Seed(seedMetadata.get(entry.getKey()), entry.getValue())).toList();
        var profile = ranker.profile(seeds);
        var pool = recommendations.candidates(new RecommendationCandidateRequest(profile.preferredChannels(), List.copyOf(excluded), CANDIDATE_LIMIT));
        List<Long> ids = ranker.rank(pool, profile, excluded, now, MIX_SIZE);
        return new Snapshot(id, signals.userId(), now, profile.personalized(), ids);
    }

    private AutomaticPlaylistResponse resolve(Snapshot snapshot) {
        // Never return cached recommendation metadata: visibility/processing may have changed since generation.
        var visible = recommendations.eligibleVideos(snapshot.videoIds()).stream().filter(RecommendationRanker::eligible)
                .collect(Collectors.toMap(VideoSummaryDto::videoId, Function.identity(), (left, right) -> left));
        List<PlaylistVideoResponse> videos = new ArrayList<>();
        for (long id : snapshot.videoIds()) {
            if (visible.containsKey(id)) videos.add(new PlaylistVideoResponse(id, snapshot.generatedAt(), videos.size(), visible.get(id)));
        }
        var metadata = new PlaylistResponse(snapshot.id(), "For you", snapshot.generatedAt(), snapshot.generatedAt(), videos.size(), snapshot.id());
        return new AutomaticPlaylistResponse(metadata, videos, snapshot.personalized(), snapshot.personalized()
                ? "Based on your recent watches, likes, channels and usual video length."
                : "Recent public videos while we learn what you enjoy.", snapshot.generatedAt(), 0, MIX_SIZE, false);
    }

    private static double decay(Instant time, Instant now, int days) {
        return Math.exp(-Math.max(0, Duration.between(time, now).toSeconds() / 86400.0) / days);
    }

    private static void requireUser(Long userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
    }
}
