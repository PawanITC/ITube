package ITubeBackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Business settings for payments and memberships. Commission is a business decision,
 * so the platform fee defaults to 0 and must be set explicitly.
 */
@ConfigurationProperties(prefix = "itube.payments")
public record PaymentProperties(
        String frontendBaseUrl,
        String currency,
        // Stripe product tax code for membership tiers; required when Managed Payments or Stripe Tax is on
        String membershipTaxCode,
        int platformFeeBps,
        boolean allowLiveMode,
        Memberships memberships,
        RateLimit rateLimit) {

    public record Memberships(
            long minPriceMinor,
            long maxPriceMinor,
            int maxTiersPerChannel,
            boolean pastDueAccess,
            Duration accessGrace) {
    }

    public record RateLimit(int maxRequests, Duration window) {
    }

    /** Platform share of an amount in minor units, rounded down in the creator's favour. */
    public long platformFee(long amountMinor) {
        return amountMinor * platformFeeBps / 10_000;
    }
}
