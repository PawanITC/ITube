package ITubeBackend.common.client;

import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoSummaryDto;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What other services may ask catalog-service (over /internal/**). Outages surface as
 * {@link DownstreamUnavailableException}; the caller decides whether to fall back or fail with 503.
 */
public interface CatalogClient {

    /** Summaries {@code viewerUserId} may see, by id. Missing, deleted or someone else's private videos are absent. */
    Map<Long, VideoSummaryDto> visibleVideoSummaries(Collection<Long> videoIds, Long viewerUserId);

    /** Who owns the video and who may play it; empty if it doesn't exist. */
    Optional<VideoAccessDto> videoAccess(Long videoId);

    Optional<ChannelDto> channel(Long channelId);

    List<ChannelDto> channelsOwnedBy(Long userId);

    /** Creates the channel unless the user already has one with that name; returns it either way. */
    ChannelDto ensureChannel(Long userId, String channelName);

    /** The video's source object key (transcode-worker, for messages from before the key was included). */
    Optional<String> sourceKey(Long videoId);
}
