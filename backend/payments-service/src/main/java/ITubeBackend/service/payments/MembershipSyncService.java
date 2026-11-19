package ITubeBackend.service.payments;

import ITubeBackend.dto.memberships.MembershipEvent;
import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.entities.ChannelMembershipTierEntity;
import ITubeBackend.entities.PaymentCustomerEntity;
import ITubeBackend.repository.ChannelMembershipRepository;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import ITubeBackend.repository.PaymentCustomerRepository;
import ITubeBackend.service.entitlement.MembershipAccessPolicy;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Rebuilds a local membership from Stripe's current copy of the Subscription.
 * <p>
 * Every webhook re-reads the Subscription instead of trusting the event payload, so duplicate and
 * out-of-order events all converge on Stripe's latest state. Kafka events are written only when
 * access actually changes, so replays never publish a second activation.
 */
@Service
public class MembershipSyncService {
    private static final Logger log = LoggerFactory.getLogger(MembershipSyncService.class);

    private final StripeApi stripe;
    private final ChannelMembershipTierRepository tierRepository;
    private final PaymentCustomerRepository customerRepository;
    private final ChannelMembershipRepository membershipRepository;
    private final MembershipAccessPolicy accessPolicy;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final String activatedTopic;
    private final String expiredTopic;
    private final String tierChangedTopic;

    public MembershipSyncService(StripeApi stripe, ChannelMembershipTierRepository tierRepository,
                                 PaymentCustomerRepository customerRepository,
                                 ChannelMembershipRepository membershipRepository,
                                 MembershipAccessPolicy accessPolicy, OutboxWriter outbox, Clock clock,
                                 @Value("${itube.memberships.activated-topic}") String activatedTopic,
                                 @Value("${itube.memberships.expired-topic}") String expiredTopic,
                                 @Value("${itube.memberships.tier-changed-topic}") String tierChangedTopic) {
        this.stripe = stripe;
        this.tierRepository = tierRepository;
        this.customerRepository = customerRepository;
        this.membershipRepository = membershipRepository;
        this.accessPolicy = accessPolicy;
        this.outbox = outbox;
        this.clock = clock;
        this.activatedTopic = activatedTopic;
        this.expiredTopic = expiredTopic;
        this.tierChangedTopic = tierChangedTopic;
    }

    /** Fetches the Subscription from Stripe and applies it. Empty if it isn't a channel membership. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ChannelMembershipEntity> sync(String subscriptionId) {
        try {
            return apply(stripe.retrieveSubscription(subscriptionId));
        } catch (StripeException exception) {
            throw new StripeCallException(exception);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ChannelMembershipEntity> apply(Subscription subscription) {
        if (subscription.getItems() == null || subscription.getItems().getData().isEmpty()) return Optional.empty();
        SubscriptionItem item = subscription.getItems().getData().getFirst();

        // Map through Stripe's object graph: Price -> Product -> tier, Customer -> user
        Optional<ChannelMembershipTierEntity> tier = tierRepository.findByStripeProductId(item.getPrice().getProduct());
        if (tier.isEmpty()) return Optional.empty(); // e.g. a future Premium subscription
        Optional<PaymentCustomerEntity> customer = customerRepository.findByStripeCustomerId(subscription.getCustomer());
        if (customer.isEmpty()) {
            log.warn("Subscription {} belongs to unknown customer {}", subscription.getId(), subscription.getCustomer());
            return Optional.empty();
        }
        Long userId = customer.get().getUserId();
        Long channelId = tier.get().getChannelId();
        String status = subscription.getStatus();
        OffsetDateTime periodEnd = item.getCurrentPeriodEnd() == null ? null
                : OffsetDateTime.ofInstant(Instant.ofEpochSecond(item.getCurrentPeriodEnd()), ZoneOffset.UTC);

        ChannelMembershipEntity membership = membershipRepository
                .findForUpdateBySubscriptionId(subscription.getId()).orElse(null);
        if (membership == null) {
            if (ChannelMembershipRepository.LIVE_STATUSES.contains(status)
                    && membershipRepository.findLive(userId, channelId).isPresent()) {
                // Checkout prevents this, but if a second subscription for the same channel slips
                // through (e.g. two tabs), undo it rather than charge twice.
                log.warn("Duplicate membership subscription {} for user {} channel {}; cancelling and refunding",
                        subscription.getId(), userId, channelId);
                try {
                    stripe.cancelAndRefundDuplicate(subscription.getId());
                } catch (StripeException exception) {
                    throw new StripeCallException(exception);
                }
                status = "canceled";
            }
            membership = new ChannelMembershipEntity(userId, channelId, subscription.getId(), subscription.getCustomer());
        }

        Long previousTierId = membership.getTierId();
        membership.setTierId(tier.get().getTierId());
        membership.setStatus(status);
        membership.setCancelAtPeriodEnd(Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));
        membership.setCurrentPeriodEnd(periodEnd);
        membership.setLastSyncedAt(OffsetDateTime.now(clock));
        membership = membershipRepository.saveAndFlush(membership);

        boolean hasAccess = accessPolicy.hasAccess(status, periodEnd);
        if (hasAccess && !membership.isEntitled()) {
            membership.setEntitled(true);
            publish(activatedTopic, "membership.activated", membership, tier.get());
        } else if (!hasAccess && membership.isEntitled()) {
            membership.setEntitled(false);
            publish(expiredTopic, "membership.expired", membership, tier.get());
        } else if (hasAccess && previousTierId != null && !previousTierId.equals(tier.get().getTierId())) {
            publish(tierChangedTopic, "membership.tier-changed", membership, tier.get());
        }
        return Optional.of(membership);
    }

    private void publish(String topic, String type, ChannelMembershipEntity membership,
                         ChannelMembershipTierEntity tier) {
        UUID eventId = UUID.randomUUID();
        outbox.write(topic, membership.getMembershipId().toString(), eventId, new MembershipEvent(
                eventId, type, 1, OffsetDateTime.now(clock), membership.getMembershipId(), membership.getUserId(),
                membership.getChannelId(), tier.getTierId(), tier.getLevel(), membership.getStatus(),
                membership.getCurrentPeriodEnd()));
        log.info("{} membership={} user={} channel={} tierLevel={}", type, membership.getMembershipId(),
                membership.getUserId(), membership.getChannelId(), tier.getLevel());
    }
}
