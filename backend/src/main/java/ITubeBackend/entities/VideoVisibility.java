package ITubeBackend.entities;

public enum VideoVisibility {
    /** Listed and playable by anyone. */
    PUBLIC,
    /** Playable by anyone with the link, not listed in feeds. */
    UNLISTED,
    /** Only the channel owner. */
    PRIVATE,
    /** Listed with a badge; playable by channel members at or above the required tier level. */
    MEMBERS_ONLY;

    public boolean openToEveryone() {
        return this == PUBLIC || this == UNLISTED;
    }
}
