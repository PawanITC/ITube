package ITubeBackend.common.client;

import ITubeBackend.common.cache.CacheConfig;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.dto.channel.EnsureChannelRequest;
import ITubeBackend.dto.video.InternalVideoSummary;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoSummaryDto;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * catalog-service over HTTP, behind retry/circuit breaker/bulkhead ({@link Downstream}) and Caffeine:
 * <ul>
 *   <li>video summaries are cached per video (30 s) and fetched in one batched call for all misses;</li>
 *   <li>video access (owner, visibility) is cached 15 s;</li>
 *   <li>channels are cached 60 s.</li>
 * </ul>
 * So a page of 50 history entries costs at most one catalog call, usually none. Changes (e.g. a video made
 * private) reach other services within the TTL.
 */
public class HttpCatalogClient implements CatalogClient {
    private static final ParameterizedTypeReference<List<InternalVideoSummary>> SUMMARIES = new ParameterizedTypeReference<>() {
    };

    private final Downstream catalog;
    private final Cache<Long, InternalVideoSummary> summaries;
    private final Cache<Long, VideoAccessDto> access;
    private final Cache<Long, ChannelDto> channels;

    public HttpCatalogClient(Downstream catalog, CacheManager cacheManager) {
        this.catalog = catalog;
        this.summaries = CacheConfig.nativeCache(cacheManager, "remote-video-summary");
        this.access = CacheConfig.nativeCache(cacheManager, "remote-video-access");
        this.channels = CacheConfig.nativeCache(cacheManager, "remote-channel");
    }

    @Override
    public Map<Long, VideoSummaryDto> visibleVideoSummaries(Collection<Long> videoIds, Long viewerUserId) {
        if (videoIds.isEmpty()) return Map.of();
        // getAll: cached ids are answered from memory; all misses are loaded in ONE request
        Map<Long, InternalVideoSummary> found = summaries.getAll(videoIds, this::fetchSummaries);
        Map<Long, VideoSummaryDto> visible = new LinkedHashMap<>();
        for (Long id : videoIds) {
            InternalVideoSummary summary = found.get(id);
            if (summary != null && summary.visibleTo(viewerUserId)) visible.put(id, summary.summary());
        }
        return visible;
    }

    private Map<Long, InternalVideoSummary> fetchSummaries(java.util.Set<? extends Long> ids) {
        List<InternalVideoSummary> body = catalog.call(() -> catalog.rest().post()
                .uri("/internal/videos/summaries")
                .body(List.copyOf(ids))
                .retrieve()
                .body(SUMMARIES));
        return body == null ? Map.of() : body.stream()
                .collect(Collectors.toMap(s -> s.summary().videoId(), Function.identity(), (a, b) -> a));
    }

    @Override
    public Optional<VideoAccessDto> videoAccess(Long videoId) {
        VideoAccessDto cached = access.getIfPresent(videoId);
        if (cached != null) return Optional.of(cached);
        Optional<VideoAccessDto> loaded = catalog.call(() -> notFoundAsEmpty(() -> catalog.rest().get()
                .uri("/internal/videos/{id}/access", videoId)
                .retrieve()
                .body(VideoAccessDto.class)));
        loaded.ifPresent(value -> access.put(videoId, value));
        return loaded;
    }

    @Override
    public Optional<ChannelDto> channel(Long channelId) {
        ChannelDto cached = channels.getIfPresent(channelId);
        if (cached != null) return Optional.of(cached);
        Optional<ChannelDto> loaded = catalog.call(() -> notFoundAsEmpty(() -> catalog.rest().get()
                .uri("/internal/channels/{id}", channelId)
                .retrieve()
                .body(ChannelDto.class)));
        loaded.ifPresent(value -> channels.put(channelId, value));
        return loaded;
    }

    @Override
    public List<ChannelDto> channelsOwnedBy(Long userId) {
        ChannelDto[] body = catalog.call(() -> catalog.rest().get()
                .uri("/internal/channels?ownerUserId={id}", userId)
                .retrieve()
                .body(ChannelDto[].class));
        return body == null ? List.of() : Arrays.asList(body);
    }

    @Override
    public ChannelDto ensureChannel(Long userId, String channelName) {
        // Safe to retry: the catalog creates the channel at most once per (user, name)
        return catalog.call(() -> catalog.rest().post()
                .uri("/internal/channels")
                .body(new EnsureChannelRequest(userId, channelName))
                .retrieve()
                .body(ChannelDto.class));
    }

    @Override
    public Optional<String> sourceKey(Long videoId) {
        @SuppressWarnings("unchecked")
        Optional<Map<String, String>> body = catalog.call(() -> notFoundAsEmpty(() -> (Map<String, String>) catalog.rest().get()
                .uri("/internal/videos/{id}/source", videoId)
                .retrieve()
                .body(Map.class)));
        return body.map(value -> value.get("videoKey"));
    }

    static <T> Optional<T> notFoundAsEmpty(java.util.function.Supplier<T> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        }
    }
}
