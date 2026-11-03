package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One charge attempt (a membership invoice now; tips and Premium later) with its fee split. */
@Entity
@Table(name = "payments")
public class PaymentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "purpose", nullable = false, length = 30)
    private String purpose;

    @Column(name = "channel_id")
    private Long channelId;

    @Column(name = "membership_id")
    private Long membershipId;

    @Column(name = "stripe_checkout_session_id", unique = true)
    private String stripeCheckoutSessionId;

    @Column(name = "stripe_invoice_id", unique = true)
    private String stripeInvoiceId;

    @Column(name = "stripe_payment_intent_id", unique = true)
    private String stripePaymentIntentId;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "platform_fee_minor", nullable = false)
    private Long platformFeeMinor = 0L;

    @Column(name = "creator_earnings_minor", nullable = false)
    private Long creatorEarningsMinor = 0L;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    public PaymentEntity() {
    }

    @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }

    public void setUserId(Long userId) { this.userId = userId; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public void setMembershipId(Long membershipId) { this.membershipId = membershipId; }
    public void setStripeInvoiceId(String stripeInvoiceId) { this.stripeInvoiceId = stripeInvoiceId; }
    public void setAmountMinor(Long amountMinor) { this.amountMinor = amountMinor; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setPlatformFeeMinor(Long platformFeeMinor) { this.platformFeeMinor = platformFeeMinor; }
    public void setCreatorEarningsMinor(Long creatorEarningsMinor) { this.creatorEarningsMinor = creatorEarningsMinor; }
    public void setStatus(String status) { this.status = status; }

    public Long getPaymentId() { return paymentId; }
    public Long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
}
