package ITubeBackend.repository;

import ITubeBackend.entities.PaymentCustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentCustomerRepository extends JpaRepository<PaymentCustomerEntity, Long> {
    Optional<PaymentCustomerEntity> findByUserId(Long userId);

    Optional<PaymentCustomerEntity> findByStripeCustomerId(String stripeCustomerId);

    /**
     * Serialises payment actions for one user inside the current transaction, so a double-click
     * can't create two Stripe customers or two Checkout Sessions. Released at commit/rollback.
     */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(:userId) AS TEXT)", nativeQuery = true)
    String lockUser(@Param("userId") long userId);
}
