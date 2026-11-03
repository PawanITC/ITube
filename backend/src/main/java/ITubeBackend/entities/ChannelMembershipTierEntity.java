package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A paid membership tier a creator offers on their channel. Each tier is one Stripe Product. */
@Entity
@Table(name = "channel_membership_tiers")
public class ChannelMembershipTierEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tier_id")
    private Long tierId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "level", nullable = false)
    private Integer level;

    @Column(name = "price_minor", nullable = false)
    private Long priceMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "stripe_product_id", unique = true)
    private String stripeProductId;

    @Column(name = "stripe_price_id", unique = true)
    private String stripePriceId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    public ChannelMembershipTierEntity() {
    }

    @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }

    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setLevel(Integer level) { this.level = level; }
    public void setPriceMinor(Long priceMinor) { this.priceMinor = priceMinor; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setStripeProductId(String stripeProductId) { this.stripeProductId = stripeProductId; }
    public void setStripePriceId(String stripePriceId) { this.stripePriceId = stripePriceId; }
    public void setActive(boolean active) { this.active = active; }

    public Long getTierId() { return tierId; }
    public Long getChannelId() { return channelId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Integer getLevel() { return level; }
    public Long getPriceMinor() { return priceMinor; }
    public String getCurrency() { return currency; }
    public String getStripeProductId() { return stripeProductId; }
    public String getStripePriceId() { return stripePriceId; }
    public boolean isActive() { return active; }
}
