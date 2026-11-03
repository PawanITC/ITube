package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A refund (one cumulative row per charge) or a dispute (one row per dispute) against a payment. */
@Entity
@Table(name = "refund_records")
public class RefundRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refund_record_id")
    private Long refundRecordId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "stripe_charge_id", nullable = false)
    private String stripeChargeId;

    @Column(name = "stripe_dispute_id", unique = true)
    private String stripeDisputeId;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 40)
    private String status;

    @Column(name = "reason", length = 100)
    private String reason;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    public RefundRecordEntity() {
    }

    public RefundRecordEntity(String kind, String stripeChargeId, String stripeDisputeId) {
        this.kind = kind;
        this.stripeChargeId = stripeChargeId;
        this.stripeDisputeId = stripeDisputeId;
    }

    @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }

    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public void setAmountMinor(Long amountMinor) { this.amountMinor = amountMinor; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setStatus(String status) { this.status = status; }
    public void setReason(String reason) { this.reason = reason; }

    public String getKind() { return kind; }
    public Long getAmountMinor() { return amountMinor; }
    public String getStatus() { return status; }
}
