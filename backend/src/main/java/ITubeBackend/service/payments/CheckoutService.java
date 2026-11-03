package ITubeBackend.service.payments;

import ITubeBackend.config.PaymentProperties;
import ITubeBackend.dto.payments.CheckoutResponse;
import ITubeBackend.dto.payments.CheckoutStatusDto;
import ITubeBackend.entities.ChannelMembershipTierEntity;
import ITubeBackend.entities.CheckoutSessionEntity;
import ITubeBackend.repository.ChannelMembershipRepository;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.CheckoutSessionRepository;
import ITubeBackend.repository.PaymentCustomerRepository;
import ITubeBackend.service.memberships.MembershipViews;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Starts Stripe-hosted Checkout. Prices always come from the database, never from the client,
 * and nothing is granted here: access is only given once Stripe's webhook confirms the payment.
 */
@Service
public class CheckoutService {
    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    static final String MEMBERSHIP = "CHANNEL_MEMBERSHIP";
    // Labels the flow in the Stripe Dashboard (integration_identifier, API 2026-03-25.dahlia+)
    static final String MEMBERSHIP_FLOW_ID = "itube-channel-membership-qhwvzmbt";
    private static final Duration SESSION_LIFETIME = Duration.ofMinutes(60);

    private final ChannelMembershipTierRepository tierRepository;
    private final ChannelRepository channelRepository;
    private final ChannelMembershipRepository membershipRepository;
    private final CheckoutSessionRepository checkoutRepository;
    private final PaymentCustomerRepository customerRepository;
    private final StripeCustomerService customerService;
    private final MembershipViews membershipViews;
    private final PaymentRateLimiter rateLimiter;
    private final StripeApi stripe;
    private final PaymentProperties properties;
    private final Clock clock;

    public CheckoutService(ChannelMembershipTierRepository tierRepository, ChannelRepository channelRepository,
                           ChannelMembershipRepository membershipRepository,
                           CheckoutSessionRepository checkoutRepository, PaymentCustomerRepository customerRepository,
                           StripeCustomerService customerService, MembershipViews membershipViews,
                           PaymentRateLimiter rateLimiter, StripeApi stripe, PaymentProperties properties, Clock clock) {
        this.tierRepository = tierRepository;
        this.channelRepository = channelRepository;
        this.membershipRepository = membershipRepository;
        this.checkoutRepository = checkoutRepository;
        this.customerRepository = customerRepository;
        this.customerService = customerService;
        this.membershipViews = membershipViews;
        this.rateLimiter = rateLimiter;
        this.stripe = stripe;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public CheckoutResponse startMembershipCheckout(long userId, long tierId) {
        rateLimiter.check("checkout", userId);
        // One payment action per user at a time, so a double-click can't open two sessions
        customerRepository.lockUser(userId);

        ChannelMembershipTierEntity tier = tierRepository.findById(tierId)
                .filter(ChannelMembershipTierEntity::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership tier not found"));
        Long channelId = tier.getChannelId();
        if (channelRepository.findOwnerUserIdByChannelId(channelId).map(owner -> owner.equals(userId)).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot join your own channel");
        }
        membershipRepository.findLive(userId, channelId).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, existing.getTierId().equals(tierId)
                    ? "You're already a member at this tier"
                    : "You're already a member of this channel. Change your tier from your memberships page.");
        });

        OffsetDateTime now = OffsetDateTime.now(clock);
        for (CheckoutSessionEntity open : checkoutRepository
                .findByUserIdAndPurposeAndChannelIdAndStatus(userId, MEMBERSHIP, channelId, "OPEN")) {
            boolean usable = open.getExpiresAt().isAfter(now.plusMinutes(5));
            if (usable && tierId == open.getTierId()) {
                return new CheckoutResponse(open.getStripeCheckoutSessionId(), open.getCheckoutUrl());
            }
            // A different tier (or nearly expired): close it so it can never be paid alongside the new one
            try {
                stripe.expireCheckoutSession(open.getStripeCheckoutSessionId());
                open.setStatus("EXPIRED");
            } catch (StripeException exception) {
                log.info("Could not expire Checkout Session {} ({}); it may have just been paid",
                        open.getStripeCheckoutSessionId(), exception.getCode());
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Your previous checkout for this channel is still being processed. Please wait a moment.");
            }
        }

        String customerId = customerService.getOrCreateCustomerId(userId);
        OffsetDateTime expiresAt = now.plus(SESSION_LIFETIME);
        String frontend = properties.frontendBaseUrl();
        // payment_method_types is deliberately omitted: Stripe picks eligible methods dynamically
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(tier.getStripePriceId())
                        .setQuantity(1L)
                        .build())
                .setClientReferenceId(Long.toString(userId))
                .putMetadata("itube_purpose", MEMBERSHIP)
                .putMetadata("itube_channel_id", channelId.toString())
                .putMetadata("itube_tier_id", tier.getTierId().toString())
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .putMetadata("itube_channel_id", channelId.toString())
                        .putMetadata("itube_tier_id", tier.getTierId().toString())
                        .build())
                .setSuccessUrl(frontend + "/memberships/checkout-complete?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(frontend + "/channel/" + channelId + "?checkout=cancelled")
                .setExpiresAt(expiresAt.toEpochSecond())
                .setIntegrationIdentifier(MEMBERSHIP_FLOW_ID)
                .build();
        try {
            Session session = stripe.createCheckoutSession(params, "checkout-membership-" + UUID.randomUUID());
            checkoutRepository.save(new CheckoutSessionEntity(session.getId(), userId, MEMBERSHIP, channelId,
                    tier.getTierId(), session.getUrl(),
                    OffsetDateTime.ofInstant(Instant.ofEpochSecond(session.getExpiresAt()), ZoneOffset.UTC)));
            return new CheckoutResponse(session.getId(), session.getUrl());
        } catch (StripeException exception) {
            throw new StripeCallException(exception);
        }
    }

    /** For the return page. Reads local state only; the webhook is what creates the membership. */
    @Transactional(readOnly = true)
    public CheckoutStatusDto getStatus(long userId, String stripeCheckoutSessionId) {
        CheckoutSessionEntity checkout = checkoutRepository.findByStripeCheckoutSessionId(stripeCheckoutSessionId)
                .filter(session -> session.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Checkout not found"));
        var membership = checkout.getChannelId() == null ? null
                : membershipRepository.findLive(userId, checkout.getChannelId()).map(membershipViews::toDto).orElse(null);
        return new CheckoutStatusDto(checkout.getStatus(), checkout.getPurpose(), checkout.getChannelId(), membership);
    }

    @Transactional(readOnly = true)
    public String createBillingPortalUrl(long userId) {
        rateLimiter.check("portal", userId);
        String customerId = customerService.requireCustomerId(userId);
        try {
            return stripe.createBillingPortalSession(customerId, properties.frontendBaseUrl() + "/memberships");
        } catch (StripeException exception) {
            throw new StripeCallException(exception);
        }
    }
}
