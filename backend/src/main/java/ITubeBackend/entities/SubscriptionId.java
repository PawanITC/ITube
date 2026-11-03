package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class SubscriptionId implements Serializable {
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "channel_id")
    private Long channelId;

    public SubscriptionId() {
    }

    public void setUserId(Long userId) { this.userId = userId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SubscriptionId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(channelId, that.channelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, channelId);
    }
}
