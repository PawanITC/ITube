package ITubeBackend.common.client;

import ITubeBackend.dto.video.RecommendationCandidate;
import ITubeBackend.dto.video.RecommendationCandidateRequest;
import ITubeBackend.dto.video.VideoSummaryDto;
import org.springframework.core.ParameterizedTypeReference;
import java.util.List;

/** Uses the same internal-token, tracing, retry, circuit breaker and timeouts as the other catalog calls. */
public class HttpRecommendationCatalogClient implements RecommendationCatalogClient {
    private final Downstream catalog;

    public HttpRecommendationCatalogClient(Downstream catalog) { this.catalog = catalog; }

    @Override
    public List<RecommendationCandidate> candidates(RecommendationCandidateRequest request) {
        var result = catalog.call(() -> catalog.rest().post().uri("/internal/recommendations/candidates")
                .body(request).retrieve().body(new ParameterizedTypeReference<List<RecommendationCandidate>>() { }));
        return result == null ? List.of() : result;
    }

    @Override
    public List<VideoSummaryDto> eligibleVideos(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        var result = catalog.call(() -> catalog.rest().post().uri("/internal/recommendations/videos")
                .body(ids).retrieve().body(new ParameterizedTypeReference<List<VideoSummaryDto>>() { }));
        return result == null ? List.of() : result;
    }
}
