package ITubeBackend.service.channel;

import ITubeBackend.dto.SubscriptionStatusDto;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.SubscriptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubscriptionService {
    private final ChannelRepository channelRepository;
    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionService(ChannelRepository channelRepository, SubscriptionRepository subscriptionRepository) {
        this.channelRepository = channelRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @Transactional
    public SubscriptionStatusDto subscribe(Long userId, Long channelId) {
        Long ownerUserId = channelRepository.findOwnerUserIdByChannelId(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        if (ownerUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot subscribe to your own channel");
        }
        subscriptionRepository.addSubscription(userId, channelId);
        return new SubscriptionStatusDto(true, subscriptionRepository.countByChannelId(channelId));
    }

    @Transactional
    public SubscriptionStatusDto unsubscribe(Long userId, Long channelId) {
        if (!channelRepository.existsById(channelId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }
        subscriptionRepository.removeSubscription(userId, channelId);
        return new SubscriptionStatusDto(false, subscriptionRepository.countByChannelId(channelId));
    }
}
