package ITubeBackend.common.client;

import ITubeBackend.dto.video.RecommendationCandidate;
import ITubeBackend.dto.video.RecommendationCandidateRequest;
import ITubeBackend.dto.video.VideoSummaryDto;
import java.util.List;

/** Recommendations never read catalog tables directly from engagement-service. */
public interface RecommendationCatalogClient {
    List<RecommendationCandidate> candidates(RecommendationCandidateRequest request);

    /** Fresh eligibility check: public, READY, with a source key. Deliberately bypasses summary caches. */
    List<VideoSummaryDto> eligibleVideos(List<Long> ids);
}
