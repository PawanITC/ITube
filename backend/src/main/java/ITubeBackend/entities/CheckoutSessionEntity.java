package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A Checkout Session iTube created. Lets repeat clicks reuse or expire an open session. */
@Entity
@Table(name = "checkout_sessions")
public class CheckoutSessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "checkout_session_id")
    private Long checkoutSessionId;

    @Column(name = "stripe_checkout_session_id", nullable = false, unique = true)
    private String stripeCheckoutSessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "purpose", nullable = false, length = 30)
    private String purpose;

    @Column(name = "channel_id")
    private Long channelId;

    @Column(name = "tier_id")
    private Long tierId;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "OPEN";

    @Column(name = "checkout_url", columnDefinition = "text")
    private String checkoutUrl;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    public CheckoutSessionEntity() {
    }

    public CheckoutSessionEntity(String stripeCheckoutSessionId, Long userId, String purpose, Long channelId,
                                 Long tierId, String checkoutUrl, OffsetDateTime expiresAt) {
        this.stripeCheckoutSessionId = stripeCheckoutSessionId;
        this.userId = userId;
        this.purpose = purpose;
        this.channelId = channelId;
        this.tierId = tierId;
        this.checkoutUrl = checkoutUrl;
        this.expiresAt = expiresAt;
    }

    @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }

    public void setStatus(String status) { this.status = status; }

    public String getStripeCheckoutSessionId() { return stripeCheckoutSessionId; }
    public Long getUserId() { return userId; }
    public String getPurpose() { return purpose; }
    public Long getChannelId() { return channelId; }
    public Long getTierId() { return tierId; }
    public String getStatus() { return status; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
