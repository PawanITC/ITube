package ITubeBackend.dto.video;

import java.time.LocalTime;

/** A public ad that may be played inside other videos. */
public record AdCandidate(Long videoId, String title, String channelName, LocalTime duration) {
}
