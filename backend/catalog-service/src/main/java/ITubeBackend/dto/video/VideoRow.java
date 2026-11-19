package ITubeBackend.dto.video;

import ITubeBackend.entities.VideoVisibility;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Comparator;

/**
 * One video as read from a shard, with what's needed to merge results from several shards (createdAt) and to
 * answer other services (ownerUserId). Converted to the public {@link VideoSummaryDto} before leaving the service.
 */
public record VideoRow(Long videoId, Long channelId, String title, String channelName, LocalTime duration,
                       boolean hasThumbnail, String processingStatus, VideoVisibility visibility,
                       Integer requiredTierLevel, Long ownerUserId, OffsetDateTime createdAt) {

    /** Newest first; ties (e.g. rows from before created_at existed) by id, newest first. */
    public static final Comparator<VideoRow> NEWEST_FIRST = Comparator
            .comparing(VideoRow::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(VideoRow::videoId, Comparator.reverseOrder());

    public VideoSummaryDto summary() {
        return new VideoSummaryDto(videoId, channelId, title, channelName, duration, hasThumbnail, processingStatus,
                visibility, requiredTierLevel);
    }

    public InternalVideoSummary internal() {
        return new InternalVideoSummary(summary(), ownerUserId);
    }
}
