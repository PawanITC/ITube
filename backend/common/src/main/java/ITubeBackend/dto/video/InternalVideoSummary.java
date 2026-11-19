package ITubeBackend.dto.video;

/**
 * Catalog -> other services: a video summary plus its owner, so the caller can cache one copy per video and
 * still apply the visibility rule (PRIVATE videos are only visible to their owner) per viewer.
 */
public record InternalVideoSummary(VideoSummaryDto summary, Long ownerUserId) {
    public boolean visibleTo(Long viewerUserId) {
        return !"PRIVATE".equals(summary.visibility()) || (viewerUserId != null && viewerUserId.equals(ownerUserId));
    }
}
