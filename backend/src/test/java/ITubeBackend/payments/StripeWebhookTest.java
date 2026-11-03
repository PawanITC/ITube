package ITubeBackend.payments;

import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.entities.PaymentEntity;
import ITubeBackend.entities.RefundRecordEntity;
import ITubeBackend.entities.WebhookInboxEntity;
import ITubeBackend.repository.CheckoutSessionRepository;
import ITubeBackend.repository.PaymentRepository;
import ITubeBackend.repository.RefundRecordRepository;
import ITubeBackend.repository.WebhookInboxRepository;
import ITubeBackend.service.payments.MembershipSyncService;
import ITubeBackend.service.payments.StripeApi;
import ITubeBackend.service.payments.StripeWebhookService;
import ITubeBackend.service.payments.WebhookEventHandler;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Invoice;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static ITubeBackend.payments.StripeFixtures.CLOCK;
import static ITubeBackend.payments.StripeFixtures.event;
import static ITubeBackend.payments.StripeFixtures.eventJson;
import static ITubeBackend.payments.StripeFixtures.paymentProperties;
import static ITubeBackend.payments.StripeFixtures.stripeProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StripeWebhookTest {
    private final StripeApi stripe = mock(StripeApi.class);
    private final WebhookInboxRepository inboxRepository = mock(WebhookInboxRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final RefundRecordRepository refundRepository = mock(RefundRecordRepository.class);
    private final MembershipSyncService membershipSync = mock(MembershipSyncService.class);
    private StripeWebhookService receiver;
    private WebhookEventHandler handler;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        receiver = new StripeWebhookService(stripe, inboxRepository, meters);
        handler = new WebhookEventHandler(inboxRepository, mock(CheckoutSessionRepository.class), paymentRepository,
                refundRepository, membershipSync, stripe, paymentProperties(true), stripeProperties(), meters, CLOCK);
    }

    @Test
    void invalidSignatureIsRejectedAndNothingIsStored() throws Exception {
        when(stripe.constructEvent(anyString(), anyString()))
                .thenThrow(new SignatureVerificationException("No signatures found matching the expected signature", "t=1,v1=bad"));

        assertThatThrownBy(() -> receiver.receive("{}".getBytes(StandardCharsets.UTF_8), "t=1,v1=bad"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(inboxRepository);
    }

    @Test
    void missingSignatureIsRejectedWithoutParsing() {
        assertThatThrownBy(() -> receiver.receive("{}".getBytes(StandardCharsets.UTF_8), null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(stripe, inboxRepository);
    }

    @Test
    void verifiedEventIsStoredByEventIdSoRedeliveriesAreDropped() throws Exception {
        String body = eventJson("evt_1", "customer.subscription.updated", "{\"id\":\"sub_1\",\"object\":\"subscription\"}");
        when(stripe.constructEvent(body, "sig")).thenReturn(event("evt_1", "customer.subscription.updated",
                "{\"id\":\"sub_1\",\"object\":\"subscription\"}"));
        when(inboxRepository.insertIfAbsent(anyString(), anyString(), anyBoolean(), anyString())).thenReturn(1, 0);

        receiver.receive(body.getBytes(StandardCharsets.UTF_8), "sig");
        receiver.receive(body.getBytes(StandardCharsets.UTF_8), "sig"); // Stripe retried

        // The raw body is what gets stored, and the unique event id makes the second insert a no-op
        verify(inboxRepository, org.mockito.Mockito.times(2)).insertIfAbsent("evt_1", "customer.subscription.updated", false, body);
    }

    @Test
    void alreadyProcessedEventIsNotProcessedAgain() {
        when(inboxRepository.lockPending(1L)).thenReturn(Optional.empty());
        handler.process(1L);
        verifyNoInteractions(stripe, membershipSync);
    }

    @Test
    void subscriptionEventResyncsFromStripe() {
        WebhookInboxEntity row = inboxRow("evt_2", "customer.subscription.updated");
        when(inboxRepository.lockPending(2L)).thenReturn(Optional.of(row));
        when(stripe.parseStoredEvent(any())).thenReturn(event("evt_2", "customer.subscription.updated",
                "{\"id\":\"sub_1\",\"object\":\"subscription\"}"));
        when(membershipSync.sync("sub_1")).thenReturn(Optional.of(membership()));

        handler.process(2L);

        verify(membershipSync).sync("sub_1");
        assertThat(row.getStatus()).isEqualTo("PROCESSED");
    }

    @Test
    void failedRenewalIsRecordedAsAFailedPayment() throws Exception {
        WebhookInboxEntity row = inboxRow("evt_3", "invoice.payment_failed");
        when(inboxRepository.lockPending(3L)).thenReturn(Optional.of(row));
        when(stripe.parseStoredEvent(any())).thenReturn(event("evt_3", "invoice.payment_failed",
                "{\"id\":\"in_1\",\"object\":\"invoice\"}"));
        when(stripe.retrieveInvoice("in_1")).thenReturn(invoice("in_1", "sub_1", 0, 499));
        when(membershipSync.sync("sub_1")).thenReturn(Optional.of(membership()));
        when(paymentRepository.findByStripeInvoiceId("in_1")).thenReturn(Optional.empty());

        handler.process(3L);

        ArgumentCaptor<PaymentEntity> saved = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().getAmountMinor()).isEqualTo(499);
        verify(membershipSync).sync("sub_1"); // moves the membership to past_due
    }

    @Test
    void paidInvoiceRecordsTheFeeSplit() throws Exception {
        WebhookInboxEntity row = inboxRow("evt_4", "invoice.paid");
        when(inboxRepository.lockPending(4L)).thenReturn(Optional.of(row));
        when(stripe.parseStoredEvent(any())).thenReturn(event("evt_4", "invoice.paid", "{\"id\":\"in_2\",\"object\":\"invoice\"}"));
        when(stripe.retrieveInvoice("in_2")).thenReturn(invoice("in_2", "sub_1", 499, 499));
        when(membershipSync.sync("sub_1")).thenReturn(Optional.of(membership()));
        when(paymentRepository.findByStripeInvoiceId("in_2")).thenReturn(Optional.empty());

        handler.process(4L);

        ArgumentCaptor<PaymentEntity> saved = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("SUCCEEDED");
        // 10% platform fee in the test settings (1000 bps), rounded down
        assertThat(ReflectionTestUtils.getField(saved.getValue(), "platformFeeMinor")).isEqualTo(49L);
        assertThat(ReflectionTestUtils.getField(saved.getValue(), "creatorEarningsMinor")).isEqualTo(450L);
    }

    @Test
    void refundIsTrackedAgainstTheMembershipPayment() throws Exception {
        WebhookInboxEntity row = inboxRow("evt_5", "charge.refunded");
        when(inboxRepository.lockPending(5L)).thenReturn(Optional.of(row));
        when(stripe.parseStoredEvent(any())).thenReturn(event("evt_5", "charge.refunded",
                "{\"id\":\"ch_1\",\"object\":\"charge\",\"payment_intent\":\"pi_1\",\"amount\":499,"
                        + "\"amount_refunded\":499,\"refunded\":true,\"currency\":\"gbp\"}"));
        PaymentEntity payment = new PaymentEntity();
        payment.setStatus("SUCCEEDED");
        when(paymentRepository.findByStripePaymentIntentId("pi_1")).thenReturn(Optional.empty());
        when(stripe.findInvoiceIdForPaymentIntent("pi_1")).thenReturn(Optional.of("in_2"));
        when(paymentRepository.findByStripeInvoiceId("in_2")).thenReturn(Optional.of(payment));
        when(refundRepository.findByStripeChargeIdAndKind("ch_1", "REFUND")).thenReturn(Optional.empty());

        handler.process(5L);

        ArgumentCaptor<RefundRecordEntity> refund = ArgumentCaptor.forClass(RefundRecordEntity.class);
        verify(refundRepository).save(refund.capture());
        assertThat(refund.getValue().getAmountMinor()).isEqualTo(499);
        assertThat(payment.getStatus()).isEqualTo("REFUNDED");
    }

    @Test
    void disputeMarksThePaymentDisputed() throws Exception {
        WebhookInboxEntity row = inboxRow("evt_6", "charge.dispute.created");
        when(inboxRepository.lockPending(6L)).thenReturn(Optional.of(row));
        when(stripe.parseStoredEvent(any())).thenReturn(event("evt_6", "charge.dispute.created",
                "{\"id\":\"dp_1\",\"object\":\"dispute\",\"charge\":\"ch_1\",\"payment_intent\":\"pi_1\","
                        + "\"amount\":499,\"currency\":\"gbp\",\"status\":\"needs_response\",\"reason\":\"fraudulent\"}"));
        PaymentEntity payment = new PaymentEntity();
        payment.setStatus("SUCCEEDED");
        when(paymentRepository.findByStripePaymentIntentId("pi_1")).thenReturn(Optional.of(payment));
        when(refundRepository.findByStripeDisputeId("dp_1")).thenReturn(Optional.empty());

        handler.process(6L);

        assertThat(payment.getStatus()).isEqualTo("DISPUTED");
        verify(refundRepository).save(any());
    }

    @Test
    void unrelatedEventsAreIgnored() {
        WebhookInboxEntity row = inboxRow("evt_7", "product.created");
        when(inboxRepository.lockPending(7L)).thenReturn(Optional.of(row));
        when(stripe.parseStoredEvent(any())).thenReturn(event("evt_7", "product.created", "{\"id\":\"prod_1\",\"object\":\"product\"}"));

        handler.process(7L);

        assertThat(row.getStatus()).isEqualTo("IGNORED");
        verify(membershipSync, never()).sync(any());
    }

    @Test
    void failuresBackOffAndEventuallyDeadLetter() {
        WebhookInboxEntity row = inboxRow("evt_8", "invoice.paid");
        when(inboxRepository.findById(8L)).thenReturn(Optional.of(row));

        for (int attempt = 0; attempt < 8; attempt++) handler.recordFailure(8L, new IllegalStateException("boom"));

        assertThat(row.getAttempts()).isEqualTo(8);
        assertThat(row.getStatus()).isEqualTo("FAILED");
    }

    private static WebhookInboxEntity inboxRow(String eventId, String type) {
        WebhookInboxEntity row = new WebhookInboxEntity();
        ReflectionTestUtils.setField(row, "stripeEventId", eventId);
        ReflectionTestUtils.setField(row, "eventType", type);
        ReflectionTestUtils.setField(row, "payload", "{}");
        ReflectionTestUtils.setField(row, "status", "RECEIVED");
        return row;
    }

    private static ChannelMembershipEntity membership() {
        ChannelMembershipEntity membership = new ChannelMembershipEntity(7L, 5L, "sub_1", "cus_1");
        ReflectionTestUtils.setField(membership, "membershipId", 101L);
        return membership;
    }

    private static Invoice invoice(String id, String subscriptionId, long paid, long due) {
        Invoice.Parent.SubscriptionDetails details = new Invoice.Parent.SubscriptionDetails();
        details.setSubscription(subscriptionId);
        Invoice.Parent parent = new Invoice.Parent();
        parent.setSubscriptionDetails(details);
        Invoice invoice = new Invoice();
        invoice.setId(id);
        invoice.setParent(parent);
        invoice.setAmountPaid(paid);
        invoice.setAmountDue(due);
        invoice.setCurrency("gbp");
        return invoice;
    }
}
