package ITubeBackend.repository;

import ITubeBackend.entities.UploadSessionEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSessionEntity, UUID> {
    List<UploadSessionEntity> findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(Long userId, OffsetDateTime now);

    List<UploadSessionEntity> findByUserIdAndFileNameAndFileSizeAndFileLastModifiedAndExpiresAtAfter(
            Long userId, String fileName, long fileSize, long fileLastModified, OffsetDateTime now);

    long countByUserIdAndExpiresAtAfter(Long userId, OffsetDateTime now);

    List<UploadSessionEntity> findByExpiresAtBefore(OffsetDateTime now, Limit limit);

    /** Unlike deleteById, says whether this call removed the row (so two concurrent completes can't both win). */
    @Modifying
    @Query("delete from UploadSessionEntity u where u.uploadSessionId = :id")
    int deleteSession(@Param("id") UUID id);
}
