package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Local copy of a viewer's paid membership, kept in sync with its Stripe Subscription.
 * Stripe is the source of truth; this row is rebuilt from Stripe on every relevant webhook.
 */
@Entity
@Table(name = "channel_memberships")
public class ChannelMembershipEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "membership_id")
    private Long membershipId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "tier_id", nullable = false)
    private Long tierId;

    @Column(name = "stripe_subscription_id", nullable = false, unique = true)
    private String stripeSubscriptionId;

    @Column(name = "stripe_customer_id", nullable = false)
    private String stripeCustomerId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    @Column(name = "current_period_end")
    private OffsetDateTime currentPeriodEnd;

    @Column(name = "entitled", nullable = false)
    private boolean entitled;

    @Column(name = "last_synced_at", nullable = false)
    private OffsetDateTime lastSyncedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    public ChannelMembershipEntity() {
    }

    public ChannelMembershipEntity(Long userId, Long channelId, String stripeSubscriptionId, String stripeCustomerId) {
        this.userId = userId;
        this.channelId = channelId;
        this.stripeSubscriptionId = stripeSubscriptionId;
        this.stripeCustomerId = stripeCustomerId;
    }

    @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }

    public void setTierId(Long tierId) { this.tierId = tierId; }
    public void setStatus(String status) { this.status = status; }
    public void setCancelAtPeriodEnd(boolean cancelAtPeriodEnd) { this.cancelAtPeriodEnd = cancelAtPeriodEnd; }
    public void setCurrentPeriodEnd(OffsetDateTime currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }
    public void setEntitled(boolean entitled) { this.entitled = entitled; }
    public void setLastSyncedAt(OffsetDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public Long getMembershipId() { return membershipId; }
    public Long getUserId() { return userId; }
    public Long getChannelId() { return channelId; }
    public Long getTierId() { return tierId; }
    public String getStripeSubscriptionId() { return stripeSubscriptionId; }
    public String getStripeCustomerId() { return stripeCustomerId; }
    public String getStatus() { return status; }
    public boolean isCancelAtPeriodEnd() { return cancelAtPeriodEnd; }
    public OffsetDateTime getCurrentPeriodEnd() { return currentPeriodEnd; }
    public boolean isEntitled() { return entitled; }
}
