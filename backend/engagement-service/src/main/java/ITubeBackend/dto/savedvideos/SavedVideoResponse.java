package ITubeBackend.dto.savedvideos;

import ITubeBackend.dto.video.VideoSummaryDto;
import java.time.Instant;

/** Unavailable videos keep their id so the user can still remove them, like History. */
public record SavedVideoResponse(long videoId, Instant savedAt, VideoSummaryDto video) { }
