package ITubeBackend.dto.entitlement;

/** payments-service's answer to "may this user play this video?". reason is set when not allowed. */
public record PlaybackDecision(boolean allowed, String reason) {
    public static PlaybackDecision allow() {
        return new PlaybackDecision(true, null);
    }

    public static PlaybackDecision deny(String reason) {
        return new PlaybackDecision(false, reason);
    }
}
