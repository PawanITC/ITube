package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** Links an iTube account to its Stripe Customer. */
@Entity
@Table(name = "payment_customers")
public class PaymentCustomerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_customer_id")
    private Long paymentCustomerId;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "stripe_customer_id", nullable = false, unique = true)
    private String stripeCustomerId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public PaymentCustomerEntity() {
    }

    public PaymentCustomerEntity(Long userId, String stripeCustomerId) {
        this.userId = userId;
        this.stripeCustomerId = stripeCustomerId;
    }

    public Long getUserId() { return userId; }
    public String getStripeCustomerId() { return stripeCustomerId; }
}
