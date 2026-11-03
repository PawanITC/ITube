package ITubeBackend.repository;

import ITubeBackend.entities.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
    Optional<PaymentEntity> findByStripeInvoiceId(String stripeInvoiceId);

    Optional<PaymentEntity> findByStripePaymentIntentId(String stripePaymentIntentId);
}
