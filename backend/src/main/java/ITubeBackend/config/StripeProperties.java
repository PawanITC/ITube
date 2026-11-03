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
        Duration webhookRetention) {

    public boolean configured() {
        return secretKey != null && !secretKey.isBlank();
    }

    public boolean liveMode() {
        return configured() && (secretKey.startsWith("sk_live_") || secretKey.startsWith("rk_live_"));
    }

    // Never print the secrets themselves
    @Override
    public String toString() {
        return "StripeProperties[configured=" + configured() + ", liveMode=" + liveMode() + "]";
    }
}
