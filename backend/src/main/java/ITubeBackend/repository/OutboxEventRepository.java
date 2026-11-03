package ITubeBackend.repository;

import ITubeBackend.entities.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {
    /** Claims a batch for this instance; rows locked by another instance are skipped. */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' AND next_attempt_at <= :now " +
            "ORDER BY outbox_event_id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEventEntity> lockReady(@Param("now") OffsetDateTime now, @Param("limit") int limit);
}
