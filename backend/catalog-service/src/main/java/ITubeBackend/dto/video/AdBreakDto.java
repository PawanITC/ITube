package ITubeBackend.dto.video;

/**
 * One ad to play during a video (GET /api/videos/{id}/ad-breaks). position is where it plays, as a fraction of the
 * video's length (0.5 = the midpoint). The ad is a public video, so sourceUrl needs no token.
 */
public record AdBreakDto(double position, Long adVideoId, String title, String channelName, String sourceUrl,
                         int durationSeconds) {
}
