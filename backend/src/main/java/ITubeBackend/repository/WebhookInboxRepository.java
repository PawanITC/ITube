package ITubeBackend.repository;

import ITubeBackend.entities.WebhookInboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface WebhookInboxRepository extends JpaRepository<WebhookInboxEntity, Long> {
    /** Returns 1 if stored, 0 if Stripe re-delivered an event we already have. */
    @Modifying
    @Query(value = "INSERT INTO webhook_inbox (stripe_event_id, event_type, livemode, payload) " +
            "VALUES (:eventId, :eventType, :livemode, :payload) ON CONFLICT (stripe_event_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId, @Param("eventType") String eventType,
                       @Param("livemode") boolean livemode, @Param("payload") String payload);

    @Query(value = "SELECT webhook_inbox_id FROM webhook_inbox WHERE status = 'RECEIVED' " +
            "AND next_attempt_at <= :now ORDER BY webhook_inbox_id LIMIT :limit", nativeQuery = true)
    List<Long> findReadyIds(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    /** Locks one pending event; another instance already processing it is skipped rather than waited for. */
    @Query(value = "SELECT * FROM webhook_inbox WHERE webhook_inbox_id = :id AND status = 'RECEIVED' " +
            "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<WebhookInboxEntity> lockPending(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM webhook_inbox WHERE status IN ('PROCESSED', 'IGNORED') AND processed_at < :cutoff",
            nativeQuery = true)
    int deleteProcessedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
