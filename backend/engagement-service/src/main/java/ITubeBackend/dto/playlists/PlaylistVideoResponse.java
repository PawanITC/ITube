package ITubeBackend.dto.playlists;

import ITubeBackend.dto.video.VideoSummaryDto;
import java.time.Instant;

/** A null video is an unavailable entry that the owner can still remove or move. */
public record PlaylistVideoResponse(long videoId, Instant addedAt, int position, VideoSummaryDto video) { }
