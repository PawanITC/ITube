package ITubeBackend.repository;

import ITubeBackend.entities.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, String> {

    /** Claims the token for one refresh: 1 if it was unused, 0 if someone used it already. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshTokenEntity t SET t.usedAt = :now WHERE t.tokenHash = :hash AND t.usedAt IS NULL")
    int markUsed(@Param("hash") String hash, @Param("now") OffsetDateTime now);

    @Modifying
    @Query("DELETE FROM RefreshTokenEntity t WHERE t.familyId = :familyId")
    int deleteFamily(@Param("familyId") UUID familyId);

    /** Old used tokens of a family: kept a while for reuse detection, then dropped so families stay small. */
    @Modifying
    @Query("DELETE FROM RefreshTokenEntity t WHERE t.familyId = :familyId AND t.usedAt < :before")
    int deleteUsedBefore(@Param("familyId") UUID familyId, @Param("before") OffsetDateTime before);

    @Modifying
    @Query("DELETE FROM RefreshTokenEntity t WHERE t.expiresAt < :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
