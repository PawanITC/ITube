package ITubeBackend.repository;

import ITubeBackend.entities.RefundRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefundRecordRepository extends JpaRepository<RefundRecordEntity, Long> {
    Optional<RefundRecordEntity> findByStripeChargeIdAndKind(String stripeChargeId, String kind);

    Optional<RefundRecordEntity> findByStripeDisputeId(String stripeDisputeId);
}
