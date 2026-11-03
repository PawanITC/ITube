package ITubeBackend.service.memberships;

import ITubeBackend.config.PaymentProperties;
import ITubeBackend.dto.memberships.ChannelTiersDto;
import ITubeBackend.dto.memberships.CreateTierRequest;
import ITubeBackend.dto.memberships.PerkDto;
import ITubeBackend.dto.memberships.PerkRequest;
import ITubeBackend.dto.memberships.TierDto;
import ITubeBackend.dto.memberships.UpdateTierRequest;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.entities.ChannelMembershipTierEntity;
import ITubeBackend.entities.MembershipPerkEntity;
import ITubeBackend.repository.ChannelMembershipRepository;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.MembershipPerkRepository;
import ITubeBackend.service.payments.StripeApi;
import ITubeBackend.service.payments.StripeCallException;
import com.stripe.exception.StripeException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Creator-side management of membership tiers. Each tier is one Stripe Product with a monthly Price. */
@Service
public class MembershipTierService {
    private final ChannelRepository channelRepository;
    private final ChannelMembershipTierRepository tierRepository;
    private final MembershipPerkRepository perkRepository;
    private final ChannelMembershipRepository membershipRepository;
    private final MembershipViews membershipViews;
    private final StripeApi stripe;
    private final PaymentProperties properties;

    public MembershipTierService(ChannelRepository channelRepository, ChannelMembershipTierRepository tierRepository,
                                 MembershipPerkRepository perkRepository,
                                 ChannelMembershipRepository membershipRepository, MembershipViews membershipViews,
                                 StripeApi stripe, PaymentProperties properties) {
        this.channelRepository = channelRepository;
        this.tierRepository = tierRepository;
        this.perkRepository = perkRepository;
        this.membershipRepository = membershipRepository;
        this.membershipViews = membershipViews;
        this.stripe = stripe;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ChannelTiersDto getChannelTiers(Long channelId, Long viewerUserId) {
        ChannelEntity channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        boolean owner = viewerUserId != null
                && viewerUserId.equals(channelRepository.findOwnerUserIdByChannelId(channelId).orElse(null));
        List<ChannelMembershipTierEntity> tiers = tierRepository.findByChannelIdAndActiveTrueOrderByLevelAsc(channelId);
        var current = viewerUserId == null ? null : membershipRepository.findLive(viewerUserId, channelId)
                .map(membershipViews::toDto).orElse(null);
        return new ChannelTiersDto(channelId, channel.getChannelName(), owner, toDtos(tiers), current);
    }

    @Transactional
    public TierDto createTier(Long userId, Long channelId, CreateTierRequest request) {
        ChannelEntity channel = requireOwnedChannel(userId, channelId);
        validatePrice(request.priceMinor());
        if (tierRepository.countByChannelIdAndActiveTrue(channelId) >= properties.memberships().maxTiersPerChannel()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This channel already has the maximum number of tiers");
        }
        if (tierRepository.existsByChannelIdAndLevelAndActiveTrue(channelId, request.level())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A tier with this level already exists");
        }

        ChannelMembershipTierEntity tier = new ChannelMembershipTierEntity();
        tier.setChannelId(channelId);
        tier.setName(request.name().trim());
        tier.setDescription(blankToNull(request.description()));
        tier.setLevel(request.level());
        tier.setPriceMinor(request.priceMinor());
        tier.setCurrency(properties.currency());
        tier = tierRepository.saveAndFlush(tier);

        // Idempotency keys get a random part: local ids aren't unique across databases (a reset dev DB or
        // a teammate's machine sharing the sandbox reuses them) and Stripe rejects a reused key with
        // different parameters. The SDK reuses each key for its own retries, so a retry still can't duplicate.
        // If this transaction then rolls back, the orphaned test Product is harmless and never sold.
        try {
            String productId = stripe.createProduct(productName(channel, tier), tier.getDescription(),
                    properties.membershipTaxCode(),
                    Map.of("itube_tier_id", tier.getTierId().toString(), "itube_channel_id", channelId.toString()),
                    "tier-product-" + tier.getTierId() + "-" + UUID.randomUUID());
            tier.setStripeProductId(productId);
            tier.setStripePriceId(stripe.createMonthlyPrice(productId, tier.getPriceMinor(), tier.getCurrency(),
                    "tier-price-" + tier.getTierId() + "-" + UUID.randomUUID()));
        } catch (StripeException exception) {
            throw new StripeCallException(exception);
        }
        Long createdTierId = tier.getTierId();
        replacePerks(createdTierId, request.perks());
        return toDtos(tierRepository.findByChannelIdAndActiveTrueOrderByLevelAsc(channelId)).stream()
                .filter(dto -> dto.tierId().equals(createdTierId)).findFirst().orElseThrow();
    }

    @Transactional
    public TierDto updateTier(Long userId, Long tierId, UpdateTierRequest request) {
        ChannelMembershipTierEntity tier = requireOwnedActiveTier(userId, tierId);
        ChannelEntity channel = channelRepository.findById(tier.getChannelId()).orElseThrow();
        try {
            if (request.name() != null || request.description() != null) {
                if (request.name() != null) tier.setName(request.name().trim());
                if (request.description() != null) tier.setDescription(blankToNull(request.description()));
                stripe.updateProduct(tier.getStripeProductId(), productName(channel, tier), tier.getDescription(),
                        properties.membershipTaxCode(), true);
            }
            if (request.priceMinor() != null && !request.priceMinor().equals(tier.getPriceMinor())) {
                validatePrice(request.priceMinor());
                // Stripe Prices are immutable: create a new one for new members, archive the old one.
                // Existing subscriptions keep their current price.
                String newPrice = stripe.createMonthlyPrice(tier.getStripeProductId(), request.priceMinor(),
                        tier.getCurrency(), "tier-price-" + tierId + "-" + request.priceMinor() + "-" + UUID.randomUUID());
                stripe.archivePrice(tier.getStripePriceId());
                tier.setStripePriceId(newPrice);
                tier.setPriceMinor(request.priceMinor());
            }
        } catch (StripeException exception) {
            throw new StripeCallException(exception);
        }
        if (request.perks() != null) replacePerks(tierId, request.perks());
        tierRepository.saveAndFlush(tier);
        return toDtos(tierRepository.findByChannelIdAndActiveTrueOrderByLevelAsc(tier.getChannelId())).stream()
                .filter(dto -> dto.tierId().equals(tierId)).findFirst().orElseThrow();
    }

    /** Stops new sign-ups. Existing members keep their subscription until they cancel. */
    @Transactional
    public void archiveTier(Long userId, Long tierId) {
        ChannelMembershipTierEntity tier = requireOwnedActiveTier(userId, tierId);
        ChannelEntity channel = channelRepository.findById(tier.getChannelId()).orElseThrow();
        tier.setActive(false);
        try {
            stripe.updateProduct(tier.getStripeProductId(), productName(channel, tier), tier.getDescription(),
                    properties.membershipTaxCode(), false);
        } catch (StripeException exception) {
            throw new StripeCallException(exception);
        }
    }

    private List<TierDto> toDtos(List<ChannelMembershipTierEntity> tiers) {
        Map<Long, List<MembershipPerkEntity>> perksByTier = perkRepository
                .findByTierIdIn(tiers.stream().map(ChannelMembershipTierEntity::getTierId).toList())
                .stream().collect(Collectors.groupingBy(MembershipPerkEntity::getTierId));
        List<TierDto> result = new ArrayList<>();
        for (ChannelMembershipTierEntity tier : tiers) {
            // Higher tiers inherit every perk from lower tiers
            List<PerkDto> perks = tiers.stream()
                    .filter(lower -> lower.getLevel() <= tier.getLevel())
                    .sorted(Comparator.comparing(ChannelMembershipTierEntity::getLevel))
                    .flatMap(lower -> perksByTier.getOrDefault(lower.getTierId(), List.of()).stream()
                            .map(perk -> new PerkDto(perk.getPerkType(), perk.getDescription(), lower.getLevel())))
                    .toList();
            result.add(new TierDto(tier.getTierId(), tier.getChannelId(), tier.getName(), tier.getDescription(),
                    tier.getLevel(), tier.getPriceMinor(), tier.getCurrency(), perks));
        }
        return result;
    }

    private void replacePerks(Long tierId, List<PerkRequest> perks) {
        perkRepository.deleteByTierId(tierId);
        if (perks == null) return;
        perkRepository.saveAll(perks.stream()
                .map(perk -> new MembershipPerkEntity(tierId, perk.type(), perk.description().trim()))
                .toList());
    }

    private ChannelEntity requireOwnedChannel(Long userId, Long channelId) {
        ChannelEntity channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        Long ownerUserId = channelRepository.findOwnerUserIdByChannelId(channelId).orElseThrow();
        if (!ownerUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel owner can manage memberships");
        }
        return channel;
    }

    private ChannelMembershipTierEntity requireOwnedActiveTier(Long userId, Long tierId) {
        ChannelMembershipTierEntity tier = tierRepository.findById(tierId)
                .filter(ChannelMembershipTierEntity::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tier not found"));
        requireOwnedChannel(userId, tier.getChannelId());
        return tier;
    }

    private void validatePrice(long priceMinor) {
        var limits = properties.memberships();
        if (priceMinor < limits.minPriceMinor() || priceMinor > limits.maxPriceMinor()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Price must be between "
                    + limits.minPriceMinor() + " and " + limits.maxPriceMinor() + " (minor units)");
        }
    }

    private static String productName(ChannelEntity channel, ChannelMembershipTierEntity tier) {
        return channel.getChannelName() + " - " + tier.getName() + " membership";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
