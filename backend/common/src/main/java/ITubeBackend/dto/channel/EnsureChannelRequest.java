package ITubeBackend.dto.channel;

/** Internal: create the channel {@code channelName} for {@code userId} unless that user already has one by that name. */
public record EnsureChannelRequest(Long userId, String channelName) {
}
