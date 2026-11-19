package ITubeBackend.service.channel;

import ITubeBackend.dto.subscribtions.SubscriptionStatusDto;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.SubscriptionRepository;
import ITubeBackend.sharding.Shards;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Free Subscribe. The row lives on the channel's shard, next to the channel and its subscriber count. */
@Service
public class SubscriptionService {
    private final ChannelRepository channelRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final Shards shards;
    private final TransactionTemplate transactions;

    public SubscriptionService(ChannelRepository channelRepository, SubscriptionRepository subscriptionRepository,
                               Shards shards, TransactionTemplate transactions) {
        this.channelRepository = channelRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.shards = shards;
        this.transactions = transactions;
    }

    public SubscriptionStatusDto subscribe(Long userId, Long channelId) {
        return shards.onShardOf(channelId, () -> transactions.execute(status -> {
            Long ownerUserId = channelRepository.findOwnerUserIdByChannelId(channelId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
            if (ownerUserId.equals(userId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot subscribe to your own channel");
            }
            subscriptionRepository.addSubscription(userId, channelId);
            return new SubscriptionStatusDto(true, subscriptionRepository.countByChannelId(channelId));
        }));
    }

    public SubscriptionStatusDto unsubscribe(Long userId, Long channelId) {
        return shards.onShardOf(channelId, () -> transactions.execute(status -> {
            if (!channelRepository.existsById(channelId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
            }
            subscriptionRepository.removeSubscription(userId, channelId);
            return new SubscriptionStatusDto(false, subscriptionRepository.countByChannelId(channelId));
        }));
    }
}
