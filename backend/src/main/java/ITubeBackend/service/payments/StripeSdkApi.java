package ITubeBackend.service.payments;

import ITubeBackend.config.PaymentProperties;
import ITubeBackend.config.StripeProperties;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.InvoicePayment;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.InvoicePaymentListParams;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.PriceUpdateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.ProductUpdateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SubscriptionCancelParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class StripeSdkApi implements StripeApi {
    private static final Logger log = LoggerFactory.getLogger(StripeSdkApi.class);

    private final StripeProperties properties;
    private final StripeClient client;

    public StripeSdkApi(StripeProperties properties, PaymentProperties paymentProperties) {
        this.properties = properties;
        if (!properties.configured()) {
            log.warn("STRIPE_SECRET_KEY is not set; payment endpoints will return 503");
            this.client = null;
            return;
        }
        if (properties.liveMode() && !paymentProperties.allowLiveMode()) {
            throw new IllegalStateException("A live Stripe key is configured but itube.payments.allow-live-mode is false");
        }
        // stripe-java pins the API version (2026-10-28.endive for 34.1.0) and retries network
        // failures, reusing the idempotency key so a retry never creates a second object.
        this.client = StripeClient.builder()
                .setApiKey(properties.secretKey())
                .setMaxNetworkRetries(2)
                .build();
        log.info("Stripe client ready ({} mode)", properties.liveMode() ? "LIVE" : "test");
    }

    private StripeClient client() {
        if (client == null) throw new PaymentsNotConfiguredException();
        return client;
    }

    private static RequestOptions idempotent(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).build();
    }

    @Override
    public String createCustomer(long userId, String email, String idempotencyKey) throws StripeException {
        // Email is sent so Stripe can email receipts. Metadata holds only the internal id.
        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(email)
                .putMetadata("itube_user_id", Long.toString(userId))
                .build();
        return client().v1().customers().create(params, idempotent(idempotencyKey)).getId();
    }

    @Override
    public Session createCheckoutSession(SessionCreateParams params, String idempotencyKey) throws StripeException {
        return client().v1().checkout().sessions().create(params, idempotent(idempotencyKey));
    }

    @Override
    public void expireCheckoutSession(String checkoutSessionId) throws StripeException {
        client().v1().checkout().sessions().expire(checkoutSessionId);
    }

    @Override
    public String createBillingPortalSession(String customerId, String returnUrl) throws StripeException {
        var params = com.stripe.param.billingportal.SessionCreateParams.builder()
                .setCustomer(customerId)
                .setReturnUrl(returnUrl)
                .build();
        return client().v1().billingPortal().sessions().create(params).getUrl();
    }

    @Override
    public String createProduct(String name, String description, Map<String, String> metadata, String idempotencyKey)
            throws StripeException {
        ProductCreateParams.Builder params = ProductCreateParams.builder().setName(name).putAllMetadata(metadata);
        if (description != null && !description.isBlank()) params.setDescription(description);
        return client().v1().products().create(params.build(), idempotent(idempotencyKey)).getId();
    }

    @Override
    public void updateProduct(String productId, String name, String description, boolean active) throws StripeException {
        ProductUpdateParams.Builder params = ProductUpdateParams.builder().setName(name).setActive(active);
        if (description != null && !description.isBlank()) params.setDescription(description);
        client().v1().products().update(productId, params.build());
    }

    @Override
    public String createMonthlyPrice(String productId, long amountMinor, String currency, String idempotencyKey)
            throws StripeException {
        PriceCreateParams params = PriceCreateParams.builder()
                .setProduct(productId)
                .setUnitAmount(amountMinor)
                .setCurrency(currency)
                .setRecurring(PriceCreateParams.Recurring.builder()
                        .setInterval(PriceCreateParams.Recurring.Interval.MONTH).build())
                .build();
        return client().v1().prices().create(params, idempotent(idempotencyKey)).getId();
    }

    @Override
    public void archivePrice(String priceId) throws StripeException {
        client().v1().prices().update(priceId, PriceUpdateParams.builder().setActive(false).build());
    }

    @Override
    public Subscription retrieveSubscription(String subscriptionId) throws StripeException {
        return client().v1().subscriptions().retrieve(subscriptionId);
    }

    @Override
    public Subscription updateSubscription(String subscriptionId, SubscriptionUpdateParams params, String idempotencyKey)
            throws StripeException {
        return client().v1().subscriptions().update(subscriptionId, params, idempotent(idempotencyKey));
    }

    @Override
    public void cancelAndRefundDuplicate(String subscriptionId) throws StripeException {
        Subscription subscription = client().v1().subscriptions().cancel(subscriptionId,
                SubscriptionCancelParams.builder().setProrate(false).build(),
                idempotent("cancel-duplicate-" + subscriptionId));
        if (subscription.getLatestInvoice() == null) return;
        InvoicePaymentListParams params = InvoicePaymentListParams.builder()
                .setInvoice(subscription.getLatestInvoice()).build();
        for (InvoicePayment payment : client().v1().invoicePayments().list(params).getData()) {
            String paymentIntent = payment.getPayment() == null ? null : payment.getPayment().getPaymentIntent();
            if (paymentIntent != null && "paid".equals(payment.getStatus())) {
                client().v1().refunds().create(RefundCreateParams.builder()
                                .setPaymentIntent(paymentIntent)
                                .setReason(RefundCreateParams.Reason.DUPLICATE).build(),
                        idempotent("refund-duplicate-" + paymentIntent));
            }
        }
    }

    @Override
    public Invoice retrieveInvoice(String invoiceId) throws StripeException {
        return client().v1().invoices().retrieve(invoiceId);
    }

    @Override
    public Optional<String> findInvoiceIdForPaymentIntent(String paymentIntentId) throws StripeException {
        InvoicePaymentListParams params = InvoicePaymentListParams.builder()
                .setPayment(InvoicePaymentListParams.Payment.builder()
                        .setType(InvoicePaymentListParams.Payment.Type.PAYMENT_INTENT)
                        .setPaymentIntent(paymentIntentId).build())
                .build();
        return client().v1().invoicePayments().list(params).getData().stream()
                .map(InvoicePayment::getInvoice).findFirst();
    }

    @Override
    public Event constructEvent(String payload, String signatureHeader) throws SignatureVerificationException {
        if (properties.webhookSecret() == null || properties.webhookSecret().isBlank()) {
            throw new PaymentsNotConfiguredException();
        }
        return client().constructEvent(payload, signatureHeader, properties.webhookSecret());
    }
}
