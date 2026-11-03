package ITubeBackend.repository;

import ITubeBackend.entities.CheckoutSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CheckoutSessionRepository extends JpaRepository<CheckoutSessionEntity, Long> {
    Optional<CheckoutSessionEntity> findByStripeCheckoutSessionId(String stripeCheckoutSessionId);

    List<CheckoutSessionEntity> findByUserIdAndPurposeAndChannelIdAndStatus(Long userId, String purpose,
                                                                         Long channelId, String status);
}
