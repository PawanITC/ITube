package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;

/** An account at an external identity provider (e.g. Cognito) linked to an ITube account. */
@Entity
@Table(name = "external_identities", schema = "identity")
public class ExternalIdentityEntity {

    @Embeddable
    public record Key(@Column(name = "provider", length = 32) String provider,
                      @Column(name = "subject", length = 255) String subject) implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "linked_at", nullable = false)
    private OffsetDateTime linkedAt;

    protected ExternalIdentityEntity() {
    }

    public ExternalIdentityEntity(Key id, Long userId, String email, OffsetDateTime linkedAt) {
        this.id = id;
        this.userId = userId;
        this.email = email;
        this.linkedAt = linkedAt;
    }

    public Key getId() { return id; }
    public Long getUserId() { return userId; }
    public String getEmail() { return email; }
    public OffsetDateTime getLinkedAt() { return linkedAt; }
}
