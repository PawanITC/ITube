package ITubeBackend.service.entitlement;

import ITubeBackend.config.PaymentProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Decides whether a subscription in a given Stripe state grants access right now.
 * Used both when syncing from Stripe (to publish activated/expired events) and at playback time.
 */
@Component
public class MembershipAccessPolicy {
    private final PaymentProperties.Memberships settings;
    private final Clock clock;

    public MembershipAccessPolicy(PaymentProperties properties, Clock clock) {
        this.settings = properties.memberships();
        this.clock = clock;
    }

    public boolean hasAccess(String stripeStatus, OffsetDateTime currentPeriodEnd) {
        boolean statusGrantsAccess = switch (stripeStatus) {
            case "active", "trialing" -> true;
            // Renewal failed and Stripe is retrying the payment
            case "past_due" -> settings.pastDueAccess();
            default -> false; // canceled, unpaid, incomplete, incomplete_expired, paused
        };
        if (!statusGrantsAccess) return false;
        // Safety net if a webhook is missed: never grant access long past the paid period
        return currentPeriodEnd == null
                || OffsetDateTime.now(clock).isBefore(currentPeriodEnd.plus(settings.accessGrace()));
    }
}
