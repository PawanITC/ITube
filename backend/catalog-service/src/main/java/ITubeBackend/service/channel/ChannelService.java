package ITubeBackend.service.channel;

import ITubeBackend.cache.CatalogCaches;
import ITubeBackend.dto.channel.ChannelDetailsDto;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.dto.channel.ChannelSearchDto;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.SubscriptionRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.sharding.IdAllocator;
import ITubeBackend.sharding.Shards;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Channels. A channel lives on its owner's shard (with all their videos), so a channel page is one database
 * query; search and "my subscriptions" ask every shard in parallel and merge.
 */
@Service
public class ChannelService {
    private static final int SEARCH_LIMIT = 20;

    private final ChannelRepository channelRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final VideoRepository videoRepository;
    private final Shards shards;
    private final IdAllocator ids;
    private final CatalogCaches caches;
    private final TransactionTemplate transactions;

    public ChannelService(ChannelRepository channelRepository, SubscriptionRepository subscriptionRepository,
                          VideoRepository videoRepository, Shards shards, IdAllocator ids, CatalogCaches caches,
                          TransactionTemplate transactions) {
        this.channelRepository = channelRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.videoRepository = videoRepository;
        this.shards = shards;
        this.ids = ids;
        this.caches = caches;
        this.transactions = transactions;
    }

    public ChannelDetailsDto getChannelDetails(Long channelId, Long currentUserId) {
        if (shards.physicalOf(channelId).isEmpty()) throw notFound();
        // Subscriptions to a channel live on the channel's shard, so this is all one database
        return shards.onShardOf(channelId, () -> transactions.execute(status -> {
            ChannelEntity channel = channelRepository.findById(channelId).orElseThrow(ChannelService::notFound);
            boolean owner = channel.getOwnerUserId().equals(currentUserId);
            boolean subscribed = currentUserId != null
                    && subscriptionRepository.existsByUserIdAndChannelId(currentUserId, channelId);
            return new ChannelDetailsDto(channelId, channel.getChannelName(),
                    subscriptionRepository.countByChannelId(channelId), subscribed, owner,
                    videoRepository.findVideoSummariesByChannelId(channelId, owner));
        }));
    }

    /** Cached: other services (payments) look channels up on every membership page. */
    public Optional<ChannelDto> findChannel(Long channelId) {
        if (shards.physicalOf(channelId).isEmpty()) return Optional.empty();
        ChannelDto cached = caches.channels.getIfPresent(channelId);
        if (cached != null) return Optional.of(cached);
        Optional<ChannelDto> loaded = shards.onShardOf(channelId, () -> channelRepository.findById(channelId))
                .map(channel -> new ChannelDto(channel.getChannelId(), channel.getOwnerUserId(), channel.getChannelName()));
        loaded.ifPresent(channel -> caches.channels.put(channelId, channel));
        return loaded;
    }

    public List<ChannelDto> channelsOwnedBy(Long userId) {
        return shards.onExistingOwnerShard(userId, () -> channelRepository.findDtosByUserId(userId), List::of);
    }

    /**
     * Creates the channel unless this user already has one with that name; returns it either way. Called by
     * identity-service when an account is created (directly and through the account.created event), so it must be
     * safe to call twice.
     */
    public ChannelDto ensureChannel(Long userId, String channelName) {
        String name = channelName == null || channelName.isBlank() ? "My channel" : channelName.trim();
        if (name.length() > 255) name = name.substring(0, 255);
        String finalName = name;
        int logicalShard = shards.logicalShardForOwner(userId);
        return shards.onOwnerShard(userId, () -> transactions.execute(status ->
                channelRepository.findDtoByOwnerAndName(userId, finalName).orElseGet(() -> {
                    ChannelEntity channel = new ChannelEntity(ids.nextChannelId(logicalShard));
                    channel.setOwnerUserId(userId);
                    channel.setChannelName(finalName);
                    channelRepository.saveAndFlush(channel);
                    return new ChannelDto(channel.getChannelId(), userId, finalName);
                })));
    }

    /** Scatter-gather: every shard returns its best matches, merged alphabetically. */
    public List<ChannelSearchDto> search(String query, Long currentUserId) {
        return shards.scatter(shard -> channelRepository.searchByChannelName(query, currentUserId, Limit.of(SEARCH_LIMIT)))
                .stream()
                .sorted(Comparator.comparing(ChannelSearchDto::channelName, String.CASE_INSENSITIVE_ORDER))
                .limit(SEARCH_LIMIT)
                .toList();
    }

    /**
     * A subscription is stored on the subscribed-to channel's shard (where its subscriber count is needed), so a
     * user's own list is gathered from every shard.
     */
    public List<ChannelDto> subscribedChannels(Long userId) {
        return shards.scatter(shard -> channelRepository.findSubscribedChannelsByUserId(userId)).stream()
                .sorted(Comparator.comparing(ChannelDto::channelName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
    }
}
