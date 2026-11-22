package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One refresh token, stored as a SHA-256 hash (ITubeBackend.auth.RefreshTokens). */
@Entity
@Table(name = "refresh_tokens", schema = "identity")
public class RefreshTokenEntity {
    @Id
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    protected RefreshTokenEntity() {
    }

    public RefreshTokenEntity(String tokenHash, UUID familyId, Long userId, OffsetDateTime createdAt,
                              OffsetDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.userId = userId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getTokenHash() { return tokenHash; }
    public UUID getFamilyId() { return familyId; }
    public Long getUserId() { return userId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getUsedAt() { return usedAt; }
}
