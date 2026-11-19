package ITubeBackend.repository;

import ITubeBackend.entities.SubscriptionEntity;
import ITubeBackend.entities.SubscriptionId;
import ITubeBackend.dto.subscribtions.SubscriptionDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, SubscriptionId> {
    @Query(value = "SELECT user_id, channel_id FROM catalog.subscriptions " +
            "WHERE user_id = :userId AND channel_id = :channelId", nativeQuery = true)
    java.util.Optional<SubscriptionDto> findDtoByIds(@Param("userId") Long userId,
                                                      @Param("channelId") Long channelId);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO catalog.subscriptions (user_id, channel_id) VALUES (:userId, :channelId) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int addSubscription(@Param("userId") Long userId, @Param("channelId") Long channelId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM catalog.subscriptions WHERE user_id = :userId AND channel_id = :channelId)",
            nativeQuery = true)
    boolean existsByUserIdAndChannelId(@Param("userId") Long userId, @Param("channelId") Long channelId);

    @Query(value = "SELECT COUNT(*) FROM catalog.subscriptions WHERE channel_id = :channelId", nativeQuery = true)
    long countByChannelId(@Param("channelId") Long channelId);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM catalog.subscriptions WHERE user_id = :userId AND channel_id = :channelId", nativeQuery = true)
    int removeSubscription(@Param("userId") Long userId, @Param("channelId") Long channelId);
}
