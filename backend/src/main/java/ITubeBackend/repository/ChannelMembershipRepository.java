package ITubeBackend.repository;

import ITubeBackend.entities.ChannelMembershipEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ChannelMembershipRepository extends JpaRepository<ChannelMembershipEntity, Long> {
    /** Statuses that still count as "a membership exists" (matches ux_memberships_live_user_channel). */
    List<String> LIVE_STATUSES = List.of("incomplete", "trialing", "active", "past_due", "unpaid", "paused");

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ChannelMembershipEntity m where m.stripeSubscriptionId = :subscriptionId")
    Optional<ChannelMembershipEntity> findForUpdateBySubscriptionId(@Param("subscriptionId") String subscriptionId);

    @Query("select m from ChannelMembershipEntity m where m.userId = :userId and m.channelId = :channelId " +
            "and m.status in ('incomplete', 'trialing', 'active', 'past_due', 'unpaid', 'paused')")
    Optional<ChannelMembershipEntity> findLive(@Param("userId") Long userId, @Param("channelId") Long channelId);

    List<ChannelMembershipEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Memberships we still treat as entitled although their paid period (plus grace) has passed. */
    @Query("select m from ChannelMembershipEntity m where m.entitled = true and m.currentPeriodEnd < :cutoff")
    List<ChannelMembershipEntity> findEntitledEndedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
