package ITubeBackend.service.payments;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;

import java.util.Map;
import java.util.Optional;

/**
 * The only place iTube talks to Stripe. Keeping the surface small makes every Stripe call easy
 * to find, and lets tests mock Stripe without network access.
 */
public interface StripeApi {
    String createCustomer(long userId, String email, String idempotencyKey) throws StripeException;

    Session createCheckoutSession(SessionCreateParams params, String idempotencyKey) throws StripeException;

    void expireCheckoutSession(String checkoutSessionId) throws StripeException;

    String createBillingPortalSession(String customerId, String returnUrl) throws StripeException;

    String createProduct(String name, String description, Map<String, String> metadata, String idempotencyKey)
            throws StripeException;

    void updateProduct(String productId, String name, String description, boolean active) throws StripeException;

    String createMonthlyPrice(String productId, long amountMinor, String currency, String idempotencyKey)
            throws StripeException;

    void archivePrice(String priceId) throws StripeException;

    Subscription retrieveSubscription(String subscriptionId) throws StripeException;

    Subscription updateSubscription(String subscriptionId, SubscriptionUpdateParams params, String idempotencyKey)
            throws StripeException;

    /** Cancels immediately and refunds the latest invoice. Only used to undo an accidental duplicate. */
    void cancelAndRefundDuplicate(String subscriptionId) throws StripeException;

    Invoice retrieveInvoice(String invoiceId) throws StripeException;

    /** Resolves a PaymentIntent to the invoice it paid, if any (subscription payments). */
    Optional<String> findInvoiceIdForPaymentIntent(String paymentIntentId) throws StripeException;

    Event constructEvent(String payload, String signatureHeader) throws SignatureVerificationException;

    /** Parses an event that was already signature-verified when it was stored in the inbox. */
    Event parseStoredEvent(String payload);
}
