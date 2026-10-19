package ITubeBackend.entities;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "subscriptions")
public class SubscriptionEntity {
    @EmbeddedId
    private SubscriptionId id;

    public SubscriptionEntity() {
    }
}
