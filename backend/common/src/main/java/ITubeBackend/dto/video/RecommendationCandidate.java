package ITubeBackend.dto.video;

import java.time.Instant;

/** Public, processed video metadata from the catalog's PostgreSQL shards. */
public record RecommendationCandidate(VideoSummaryDto video, Instant publishedAt) { }
