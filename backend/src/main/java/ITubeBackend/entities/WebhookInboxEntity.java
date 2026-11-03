package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A verified Stripe event, stored before Stripe gets its 200 response and processed afterwards. */
@Entity
@Table(name = "webhook_inbox")
public class WebhookInboxEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "webhook_inbox_id")
    private Long webhookInboxId;

    @Column(name = "stripe_event_id", nullable = false, unique = true)
    private String stripeEventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "livemode", nullable = false)
    private boolean livemode;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "received_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    public WebhookInboxEntity() {
    }

    public void markDone(String status, OffsetDateTime now) {
        this.status = status;
        this.processedAt = now;
        this.lastError = null;
    }

    public void markFailedAttempt(String error, OffsetDateTime nextAttemptAt, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        this.nextAttemptAt = nextAttemptAt;
        if (attempts >= maxAttempts) this.status = "FAILED";
    }

    public Long getWebhookInboxId() { return webhookInboxId; }
    public String getStripeEventId() { return stripeEventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
}
