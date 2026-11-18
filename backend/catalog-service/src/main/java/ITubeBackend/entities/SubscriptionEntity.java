package ITubeBackend.entities;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "subscriptions", schema = "catalog")
public class SubscriptionEntity {
    @EmbeddedId
    private SubscriptionId id;

    public SubscriptionEntity() {
    }
}
