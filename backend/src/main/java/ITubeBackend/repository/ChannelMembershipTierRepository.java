package ITubeBackend.repository;

import ITubeBackend.entities.ChannelMembershipTierEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelMembershipTierRepository extends JpaRepository<ChannelMembershipTierEntity, Long> {
    List<ChannelMembershipTierEntity> findByChannelIdAndActiveTrueOrderByLevelAsc(Long channelId);

    long countByChannelIdAndActiveTrue(Long channelId);

    boolean existsByChannelIdAndLevelAndActiveTrue(Long channelId, Integer level);

    /** Maps a Stripe Subscription back to a tier through its Price's Product (not metadata). */
    Optional<ChannelMembershipTierEntity> findByStripeProductId(String stripeProductId);
}
