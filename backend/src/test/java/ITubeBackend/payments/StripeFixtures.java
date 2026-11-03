package ITubeBackend.payments;

import ITubeBackend.config.PaymentProperties;
import ITubeBackend.config.StripeProperties;
import com.stripe.Stripe;
import com.stripe.model.Event;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.net.ApiResource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Builds Stripe objects and settings for tests without touching the network. */
public final class StripeFixtures {
    public static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private StripeFixtures() {
    }

    public static PaymentProperties paymentProperties(boolean pastDueAccess) {
        return new PaymentProperties("http://localhost:3000", "gbp", 1000, false,
                new PaymentProperties.Memberships(99, 49_999, 6, pastDueAccess, Duration.ofHours(24)),
                new PaymentProperties.RateLimit(10, Duration.ofMinutes(10)));
    }

    public static StripeProperties stripeProperties() {
        return new StripeProperties("sk_test_dummy", "whsec_dummy", 8, Duration.ofDays(30));
    }

    public static OffsetDateTime inDays(long days) {
        return OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(days);
    }

    public static Subscription subscription(String id, String customerId, String productId, String status,
                                            OffsetDateTime periodEnd, boolean cancelAtPeriodEnd) {
        Price price = new Price();
        price.setId("price_for_" + productId);
        price.setProduct(productId);
        SubscriptionItem item = new SubscriptionItem();
        item.setId("si_" + id);
        item.setPrice(price);
        item.setCurrentPeriodEnd(periodEnd == null ? null : periodEnd.toEpochSecond());
        SubscriptionItemCollection items = new SubscriptionItemCollection();
        items.setData(List.of(item));
        Subscription subscription = new Subscription();
        subscription.setId(id);
        subscription.setCustomer(customerId);
        subscription.setStatus(status);
        subscription.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        subscription.setItems(items);
        return subscription;
    }

    /** A real Stripe Event, as the SDK would parse it from a webhook body. */
    public static Event event(String id, String type, String dataObjectJson) {
        return ApiResource.GSON.fromJson(eventJson(id, type, dataObjectJson), Event.class);
    }

    public static String eventJson(String id, String type, String dataObjectJson) {
        return "{\"id\":\"" + id + "\",\"object\":\"event\",\"type\":\"" + type + "\",\"api_version\":\""
                + Stripe.API_VERSION + "\",\"livemode\":false,\"created\":1762171200,\"data\":{\"object\":"
                + dataObjectJson + "}}";
    }
}
