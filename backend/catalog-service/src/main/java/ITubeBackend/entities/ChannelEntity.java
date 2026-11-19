package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * The owner is a plain account id, not a JPA link to AccountEntity: accounts live in the identity
 * schema and may move to their own database, so the catalog never joins across to them.
 * <p>
 * The id is assigned by {@link ITubeBackend.sharding.IdAllocator} (it encodes the shard), not by the database.
 */
@Entity
@Table(name = "channel", schema = "catalog")
public class ChannelEntity implements Persistable<Long> {
    @Id
    @Column(name = "channel_id")
    private Long channelId;

    @Transient
    private boolean isNew = true;

    @Column(name = "user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "channel_name", nullable = false, length = 255)
    private String channelName;

    public ChannelEntity() {
    }

    public ChannelEntity(Long channelId) {
        this.channelId = channelId;
    }

    @Override
    public Long getId() { return channelId; }

    /** Assigned ids: tell Spring Data to INSERT (persist) rather than look the row up first (merge). */
    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }

    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public Long getChannelId() { return channelId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public String getChannelName() { return channelName; }
}
