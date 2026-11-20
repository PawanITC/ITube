package ITubeBackend.entities;

import java.time.LocalTime;

/** What an upload is: a normal video, or an ad that can be played inside other videos. */
public enum VideoType {
    VIDEO,
    /** At most {@link #MAX_AD_DURATION} long; never shows ads itself. */
    AD;

    public static final LocalTime MAX_AD_DURATION = LocalTime.of(0, 1);
}
