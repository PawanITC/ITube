package ITubeBackend.service.video;

import ITubeBackend.dto.video.RecommendationCandidate;
import ITubeBackend.dto.video.RecommendationCandidateRequest;
import ITubeBackend.dto.video.VideoRow;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.sharding.Shards;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded queries on the catalog's existing PostgreSQL shard routing; no engagement data crosses here. */
@Service
public class RecommendationCatalogService {
    private final VideoRepository videos;
    private final Shards shards;

    public RecommendationCatalogService(VideoRepository videos, Shards shards) {
        this.videos = videos;
        this.shards = shards;
    }

    public List<RecommendationCandidate> candidates(RecommendationCandidateRequest request) {
        // Real ids are positive. A sentinel avoids dialect-dependent empty NOT IN expressions.
        List<Long> excluded = request.excludedVideoIds().isEmpty() ? List.of(0L) : request.excludedVideoIds();
        var selected = new LinkedHashMap<Long, VideoRow>();
        if (!request.preferredChannelIds().isEmpty()) {
            List<VideoRow> preferred = shards.scatter(ignored -> videos.findRecommendationCandidatesByChannel(
                    request.preferredChannelIds(), excluded, Limit.of(request.limit())));
            preferred.stream().sorted(VideoRow.NEWEST_FIRST).limit(Math.max(1, request.limit() / 2))
                    .forEach(row -> selected.putIfAbsent(row.videoId(), row));
        }
        List<VideoRow> recent = shards.scatter(ignored -> videos.findRecommendationCandidates(excluded, Limit.of(request.limit())));
        recent.stream().sorted(VideoRow.NEWEST_FIRST).forEach(row -> {
            if (selected.size() < request.limit()) selected.putIfAbsent(row.videoId(), row);
        });
        return selected.values().stream().map(row -> new RecommendationCandidate(row.summary(),
                row.createdAt() == null ? null : row.createdAt().toInstant())).toList();
    }

    public List<VideoSummaryDto> eligibleVideos(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        var grouped = shards.groupByShard(ids);
        return shards.scatterTo(grouped.keySet(), shard -> videos.findEligibleRecommendationRows(grouped.get(shard)))
                .stream().map(VideoRow::summary).toList();
    }
}
