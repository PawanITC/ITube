package ITubeBackend.service.payments;

import ITubeBackend.config.PaymentProperties;
import ITubeBackend.config.StripeProperties;
import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.entities.PaymentEntity;
import ITubeBackend.entities.RefundRecordEntity;
import ITubeBackend.entities.WebhookInboxEntity;
import ITubeBackend.repository.CheckoutSessionRepository;
import ITubeBackend.repository.PaymentRepository;
import ITubeBackend.repository.RefundRecordRepository;
import ITubeBackend.repository.WebhookInboxRepository;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import com.stripe.model.radar.EarlyFraudWarning;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Processes one stored Stripe event inside a single database transaction. */
@Component
public class WebhookEventHandler {
    private static final Logger log = LoggerFactory.getLogger(WebhookEventHandler.class);
    private static final Set<String> SUBSCRIPTION_EVENTS = Set.of(
            "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted",
            "customer.subscription.paused", "customer.subscription.resumed");
    // Payment states a later, less important event must not overwrite
    private static final List<String> TERMINAL_PAYMENT_STATES = List.of("REFUNDED", "PARTIALLY_REFUNDED", "DISPUTED");

    private final WebhookInboxRepository inboxRepository;
    private final CheckoutSessionRepository checkoutRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRecordRepository refundRepository;
    private final MembershipSyncService membershipSync;
    private final StripeApi stripe;
    private final PaymentProperties paymentProperties;
    private final StripeProperties stripeProperties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public WebhookEventHandler(WebhookInboxRepository inboxRepository, CheckoutSessionRepository checkoutRepository,
                               PaymentRepository paymentRepository, RefundRecordRepository refundRepository,
                               MembershipSyncService membershipSync, StripeApi stripe,
                               PaymentProperties paymentProperties, StripeProperties stripeProperties,
                               MeterRegistry meterRegistry, Clock clock) {
        this.inboxRepository = inboxRepository;
        this.checkoutRepository = checkoutRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.membershipSync = membershipSync;
        this.stripe = stripe;
        this.paymentProperties = paymentProperties;
        this.stripeProperties = stripeProperties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Transactional
    public void process(Long inboxId) {
        // Skips rows another instance is already processing, or that are no longer pending
        Optional<WebhookInboxEntity> locked = inboxRepository.lockPending(inboxId);
        if (locked.isEmpty()) return;
        WebhookInboxEntity row = locked.get();
        Event event = stripe.parseStoredEvent(row.getPayload());
        String outcome = dispatch(event);
        row.markDone(outcome, OffsetDateTime.now(clock));
        meterRegistry.counter("itube.stripe.webhook.processed", "type", event.getType(), "outcome", outcome).increment();
    }

    /** Called after {@link #process} threw; its transaction has already rolled back. */
    @Transactional
    public void recordFailure(Long inboxId, Exception exception) {
        inboxRepository.findById(inboxId).filter(row -> "RECEIVED".equals(row.getStatus())).ifPresent(row -> {
            Duration backoff = Duration.ofMinutes(Math.min(60, 1L << Math.min(row.getAttempts(), 6)));
            row.markFailedAttempt(exception.toString(), OffsetDateTime.now(clock).plus(backoff),
                    stripeProperties.webhookMaxAttempts());
            if ("FAILED".equals(row.getStatus())) {
                meterRegistry.counter("itube.stripe.webhook.failed", "type", row.getEventType()).increment();
                log.error("Stripe event {} ({}) moved to FAILED after {} attempts; replay it once fixed",
                        row.getStripeEventId(), row.getEventType(), row.getAttempts());
            }
        });
    }

    String dispatch(Event event) {
        String type = event.getType();
        if (SUBSCRIPTION_EVENTS.contains(type)) {
            return membershipSync.sync(((Subscription) dataObject(event)).getId()).isPresent() ? "PROCESSED" : "IGNORED";
        }
        return switch (type) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded",
                 "checkout.session.async_payment_failed" -> handleCheckoutCompleted((Session) dataObject(event));
            case "checkout.session.expired" -> handleCheckoutExpired((Session) dataObject(event));
            case "invoice.paid", "invoice.payment_failed" ->
                    handleInvoice(((Invoice) dataObject(event)).getId(), "invoice.paid".equals(type));
            case "charge.refunded" -> handleRefund((Charge) dataObject(event));
            case "charge.dispute.created", "charge.dispute.updated", "charge.dispute.closed" ->
                    handleDispute((Dispute) dataObject(event));
            case "radar.early_fraud_warning.created" -> {
                EarlyFraudWarning warning = (EarlyFraudWarning) dataObject(event);
                log.warn("Early fraud warning {} on charge {}: review and refund if appropriate",
                        warning.getId(), warning.getCharge());
                yield "PROCESSED";
            }
            default -> "IGNORED";
        };
    }

    private String handleCheckoutCompleted(Session session) {
        checkoutRepository.findByStripeCheckoutSessionId(session.getId())
                .ifPresent(checkout -> checkout.setStatus("COMPLETE"));
        // Access is decided by the Subscription's status, never by the session alone: an unpaid
        // (delayed) payment leaves the subscription "incomplete", which grants nothing.
        if ("subscription".equals(session.getMode()) && session.getSubscription() != null) {
            return membershipSync.sync(session.getSubscription()).isPresent() ? "PROCESSED" : "IGNORED";
        }
        return "IGNORED"; // one-time payments (tips) arrive in Phase 4
    }

    private String handleCheckoutExpired(Session session) {
        return checkoutRepository.findByStripeCheckoutSessionId(session.getId()).map(checkout -> {
            checkout.setStatus("EXPIRED");
            return "PROCESSED";
        }).orElse("IGNORED");
    }

    private String handleInvoice(String invoiceId, boolean paid) {
        Invoice invoice = call(() -> stripe.retrieveInvoice(invoiceId));
        String subscriptionId = invoice.getParent() == null || invoice.getParent().getSubscriptionDetails() == null
                ? null : invoice.getParent().getSubscriptionDetails().getSubscription();
        if (subscriptionId == null) return "IGNORED";
        // A failed renewal moves the subscription to past_due; the sync applies the access policy
        Optional<ChannelMembershipEntity> membership = membershipSync.sync(subscriptionId);
        if (membership.isEmpty()) return "IGNORED";

        PaymentEntity payment = paymentRepository.findByStripeInvoiceId(invoiceId).orElseGet(PaymentEntity::new);
        if (payment.getStatus() != null
                && (TERMINAL_PAYMENT_STATES.contains(payment.getStatus()) || (!paid && "SUCCEEDED".equals(payment.getStatus())))) {
            return "PROCESSED"; // an older event arriving late must not undo a newer state
        }
        long amount = paid ? invoice.getAmountPaid() : invoice.getAmountDue();
        long platformFee = paymentProperties.platformFee(amount);
        payment.setPurpose("CHANNEL_MEMBERSHIP");
        payment.setUserId(membership.get().getUserId());
        payment.setChannelId(membership.get().getChannelId());
        payment.setMembershipId(membership.get().getMembershipId());
        payment.setStripeInvoiceId(invoiceId);
        payment.setAmountMinor(amount);
        payment.setCurrency(invoice.getCurrency());
        payment.setPlatformFeeMinor(platformFee);
        payment.setCreatorEarningsMinor(amount - platformFee);
        payment.setStatus(paid ? "SUCCEEDED" : "FAILED");
        paymentRepository.save(payment);
        return "PROCESSED";
    }

    private String handleRefund(Charge charge) {
        Optional<PaymentEntity> payment = findPaymentForPaymentIntent(charge.getPaymentIntent());
        if (payment.isEmpty()) return "IGNORED";
        boolean full = Boolean.TRUE.equals(charge.getRefunded());
        // One cumulative row per charge: amount_refunded is a running total, so replays are harmless
        RefundRecordEntity record = refundRepository.findByStripeChargeIdAndKind(charge.getId(), "REFUND")
                .orElseGet(() -> new RefundRecordEntity("REFUND", charge.getId(), null));
        record.setPaymentId(payment.get().getPaymentId());
        record.setAmountMinor(charge.getAmountRefunded());
        record.setCurrency(charge.getCurrency());
        record.setStatus(full ? "FULL" : "PARTIAL");
        refundRepository.save(record);
        if (!"DISPUTED".equals(payment.get().getStatus())) {
            payment.get().setStatus(full ? "REFUNDED" : "PARTIALLY_REFUNDED");
        }
        // A refund doesn't cancel the subscription; support cancels it in Stripe if access should end,
        // and the resulting customer.subscription.deleted event removes access.
        log.info("Refund recorded for payment {} ({}): {} {}", payment.get().getPaymentId(),
                full ? "full" : "partial", charge.getAmountRefunded(), charge.getCurrency());
        return "PROCESSED";
    }

    private String handleDispute(Dispute dispute) {
        Optional<PaymentEntity> payment = findPaymentForPaymentIntent(dispute.getPaymentIntent());
        if (payment.isEmpty()) return "IGNORED";
        RefundRecordEntity record = refundRepository.findByStripeDisputeId(dispute.getId())
                .orElseGet(() -> new RefundRecordEntity("DISPUTE", dispute.getCharge(), dispute.getId()));
        record.setPaymentId(payment.get().getPaymentId());
        record.setAmountMinor(dispute.getAmount());
        record.setCurrency(dispute.getCurrency());
        record.setStatus(dispute.getStatus());
        record.setReason(dispute.getReason());
        refundRepository.save(record);
        payment.get().setStatus("won".equals(dispute.getStatus()) ? "SUCCEEDED" : "DISPUTED");
        log.warn("Dispute {} on payment {} is {} (reason {}); respond in the Stripe Dashboard",
                dispute.getId(), payment.get().getPaymentId(), dispute.getStatus(), dispute.getReason());
        return "PROCESSED";
    }

    /** PaymentIntent -> invoice (subscription payments) -> local payment, falling back to a direct match. */
    private Optional<PaymentEntity> findPaymentForPaymentIntent(String paymentIntentId) {
        if (paymentIntentId == null) return Optional.empty();
        Optional<PaymentEntity> direct = paymentRepository.findByStripePaymentIntentId(paymentIntentId);
        if (direct.isPresent()) return direct;
        return call(() -> stripe.findInvoiceIdForPaymentIntent(paymentIntentId))
                .flatMap(paymentRepository::findByStripeInvoiceId);
    }

    private static StripeObject dataObject(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        // getObject() is empty when the event's API version differs from the SDK's; the fields
        // read here (ids, statuses, amounts) are stable across versions, so fall back to a lenient parse.
        return deserializer.getObject().orElseGet(() -> {
            try {
                return deserializer.deserializeUnsafe();
            } catch (EventDataObjectDeserializationException exception) {
                throw new IllegalStateException("Cannot read data of event " + event.getId(), exception);
            }
        });
    }

    private interface StripeCall<T> {
        T run() throws StripeException;
    }

    private static <T> T call(StripeCall<T> call) {
        try {
            return call.run();
        } catch (StripeException exception) {
            throw new StripeCallException(exception);
        }
    }
}
