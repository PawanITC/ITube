package ITubeBackend.service.channel;

import ITubeBackend.dto.ChannelDetailsDto;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.SubscriptionRepository;
import ITubeBackend.repository.VideoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChannelService {
    private final ChannelRepository channelRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final VideoRepository videoRepository;

    public ChannelService(ChannelRepository channelRepository, SubscriptionRepository subscriptionRepository,
                          VideoRepository videoRepository) {
        this.channelRepository = channelRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.videoRepository = videoRepository;
    }

    @Transactional(readOnly = true)
    public ChannelDetailsDto getChannelDetails(Long channelId, Long currentUserId) {
        ChannelEntity channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        Long ownerUserId = channelRepository.findOwnerUserIdByChannelId(channelId).orElseThrow();
        boolean owner = currentUserId != null && ownerUserId.equals(currentUserId);
        boolean subscribed = currentUserId != null
                && subscriptionRepository.existsByUserIdAndChannelId(currentUserId, channelId);

        return new ChannelDetailsDto(channelId, channel.getChannelName(),
                subscriptionRepository.countByChannelId(channelId), subscribed, owner,
                videoRepository.findVideoSummariesByChannelId(channelId));
    }
}
