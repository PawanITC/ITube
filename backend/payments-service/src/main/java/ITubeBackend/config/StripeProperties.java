package ITubeBackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Stripe credentials and webhook settings. Values come from environment variables
 * (STRIPE_SECRET_KEY, STRIPE_WEBHOOK_SECRET) and must never be committed or logged.
 */
@ConfigurationProperties(prefix = "itube.stripe")
public record StripeProperties(
        String secretKey,
        String webhookSecret,
        int webhookMaxAttempts,
        Duration webhookRetention,
        boolean detailedErrors) {

    public boolean configured() {
        return secretKey != null && !secretKey.isBlank();
    }

    public boolean liveMode() {
        return configured() && (secretKey.startsWith("sk_live_") || secretKey.startsWith("rk_live_"));
    }

    /** Show Stripe's own error reason to API callers. Development aid; never with a live key. */
    public boolean showDetailedErrors() {
        return detailedErrors && !liveMode();
    }

    // Never print the secrets themselves
    @Override
    public String toString() {
        return "StripeProperties[configured=" + configured() + ", liveMode=" + liveMode() + "]";
    }
}
