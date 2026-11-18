package ITubeBackend.service.memberships;

import ITubeBackend.dto.memberships.MembershipDto;
import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.entities.ChannelMembershipTierEntity;
import ITubeBackend.repository.ChannelMembershipRepository;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import ITubeBackend.repository.PaymentCustomerRepository;
import ITubeBackend.service.payments.MembershipSyncService;
import ITubeBackend.service.payments.PaymentRateLimiter;
import ITubeBackend.service.payments.StripeApi;
import ITubeBackend.service.payments.StripeCallException;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.param.SubscriptionUpdateParams;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Viewer-side management of paid memberships: list, upgrade/downgrade, cancel and resume. */
@Service
public class MembershipService {
    private static final Set<String> CHANGEABLE_STATUSES = Set.of("active", "trialing");

    private final ChannelMembershipRepository membershipRepository;
    private final ChannelMembershipTierRepository tierRepository;
    private final PaymentCustomerRepository customerRepository;
    private final MembershipSyncService membershipSync;
    private final MembershipViews membershipViews;
    private final PaymentRateLimiter rateLimiter;
    private final StripeApi stripe;

    public MembershipService(ChannelMembershipRepository membershipRepository,
                             ChannelMembershipTierRepository tierRepository,
                             PaymentCustomerRepository customerRepository, MembershipSyncService membershipSync,
                             MembershipViews membershipViews, PaymentRateLimiter rateLimiter, StripeApi stripe) {
        this.membershipRepository = membershipRepository;
        this.tierRepository = tierRepository;
        this.customerRepository = customerRepository;
        this.membershipSync = membershipSync;
        this.membershipViews = membershipViews;
        this.rateLimiter = rateLimiter;
        this.stripe = stripe;
    }

    @Transactional(readOnly = true)
    public List<MembershipDto> getMyMemberships(long userId) {
        return membershipRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(membershipViews::toDto).toList();
    }

    /**
     * Upgrades are charged the prorated difference immediately and only applied if that payment
     * succeeds. Downgrades apply immediately with the unused amount credited to the next invoice.
     */
    @Transactional
    public MembershipDto changeTier(long userId, long membershipId, long newTierId) {
        rateLimiter.check("change-tier", userId);
        customerRepository.lockUser(userId);
        ChannelMembershipEntity membership = requireOwnMembership(userId, membershipId);
        if (!CHANGEABLE_STATUSES.contains(membership.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only active memberships can change tier");
        }
        ChannelMembershipTierEntity current = tierRepository.findById(membership.getTierId()).orElseThrow();
        ChannelMembershipTierEntity target = tierRepository.findById(newTierId)
                .filter(ChannelMembershipTierEntity::isActive)
                .filter(tier -> tier.getChannelId().equals(membership.getChannelId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tier not found on this channel"));
        if (target.getTierId().equals(current.getTierId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You're already on this tier");
        }
        boolean upgrade = target.getLevel() > current.getLevel();

        try {
            Subscription subscription = stripe.retrieveSubscription(membership.getStripeSubscriptionId());
            SubscriptionItem item = subscription.getItems().getData().getFirst();
            SubscriptionUpdateParams.Builder params = SubscriptionUpdateParams.builder()
                    .addItem(SubscriptionUpdateParams.Item.builder()
                            .setId(item.getId())
                            .setPrice(target.getStripePriceId())
                            .build());
            if (upgrade) {
                params.setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE)
                        .setPaymentBehavior(SubscriptionUpdateParams.PaymentBehavior.PENDING_IF_INCOMPLETE);
            } else {
                params.setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS);
            }
            Subscription updated = stripe.updateSubscription(subscription.getId(), params.build(),
                    "change-tier-" + membershipId + "-" + newTierId + "-" + UUID.randomUUID());
            if (updated.getPendingUpdate() != null) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "The upgrade payment didn't go through, so your tier hasn't changed. Update your payment method and try again.");
            }
            return membershipViews.toDto(membershipSync.apply(updated).orElseThrow());
        } catch (StripeException exception) {
            throw new StripeCallException(exception);
        }
    }

    /** Cancels at the end of the paid period; access continues until then. */
    @Transactional
    public MembershipDto cancel(long userId, long membershipId) {
        return setCancelAtPeriodEnd(userId, membershipId, true);
    }

    @Transactional
    public MembershipDto resume(long userId, long membershipId) {
        return setCancelAtPeriodEnd(userId, membershipId, false);
    }

    private MembershipDto setCancelAtPeriodEnd(long userId, long membershipId, boolean cancel) {
        rateLimiter.check("cancel", userId);
        ChannelMembershipEntity membership = requireOwnMembership(userId, membershipId);
        if (!ChannelMembershipRepository.LIVE_STATUSES.contains(membership.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This membership has already ended");
        }
        try {
            Subscription updated = stripe.updateSubscription(membership.getStripeSubscriptionId(),
                    SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(cancel).build(),
                    (cancel ? "cancel-" : "resume-") + membershipId + "-" + UUID.randomUUID());
            return membershipViews.toDto(membershipSync.apply(updated).orElseThrow());
        } catch (StripeException exception) {
            throw new StripeCallException(exception);
        }
    }

    private ChannelMembershipEntity requireOwnMembership(long userId, long membershipId) {
        // 404 rather than 403 so other users' membership ids aren't confirmed
        return membershipRepository.findById(membershipId)
                .filter(membership -> membership.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership not found"));
    }
}
