package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The owner is a plain account id, not a JPA link to AccountEntity: accounts live in the identity
 * schema and may move to their own database, so the catalog never joins across to them.
 */
@Entity
@Table(name = "channel", schema = "catalog")
public class ChannelEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "channel_id")
    private Long channelId;

    @Column(name = "user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "channel_name", nullable = false, length = 255)
    private String channelName;

    public ChannelEntity() {
    }

    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public Long getChannelId() { return channelId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public String getChannelName() { return channelName; }
}
