package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "channel")
public class ChannelEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "channel_id")
    private Long channelId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AccountEntity user;

    @Column(name = "channel_name", nullable = false, length = 255)
    private String channelName;

    public ChannelEntity() {
    }

    public void setUser(AccountEntity user) { this.user = user; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public Long getChannelId() { return channelId; }
    public String getChannelName() { return channelName; }
}
