package ITubeBackend.dto;

import ITubeBackend.dto.video.VideoSummaryDto;

import java.util.List;

public record ChannelDetailsDto(
        Long channelId,
        String channelName,
        long subscriberCount,
        boolean subscribed,
        boolean owner,
        List<VideoSummaryDto> videos) {
}
