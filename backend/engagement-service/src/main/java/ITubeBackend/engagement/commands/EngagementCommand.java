package ITubeBackend.engagement.commands;

import java.time.Instant;
import java.util.UUID;

/** Immutable, versioned intent. Identity comes from the verified access token, never request JSON. */
public record EngagementCommand(int schemaVersion, UUID operationId, long userId, Action action,
        Long videoId, UUID resourceId, String title, Integer position, UUID expectedVersion, Instant occurredAt) {
    public enum Action {
        HISTORY_ADD, HISTORY_REMOVE, HISTORY_CLEAR, LIKE, UNLIKE, SAVE, UNSAVE, SAVED_CLEAR,
        PLAYLIST_CREATE, PLAYLIST_RENAME, PLAYLIST_DELETE, PLAYLIST_ADD, PLAYLIST_REMOVE, PLAYLIST_MOVE,
        AUTOMATIC_REFRESH
    }
    /** Ignore server-generated timestamps/ids when comparing HTTP retries of the same intent. */
    public boolean sameIntent(EngagementCommand other) {
        return userId == other.userId && action == other.action
                && java.util.Objects.equals(videoId, other.videoId)
                && ((action == Action.HISTORY_ADD || action == Action.PLAYLIST_CREATE)
                    || java.util.Objects.equals(resourceId, other.resourceId))
                && java.util.Objects.equals(title, other.title)
                && java.util.Objects.equals(position, other.position)
                && java.util.Objects.equals(expectedVersion, other.expectedVersion);
    }
}
