package ITubeBackend.dto.playlists;

import java.time.Instant;
import java.util.List;

/** Same playlist/video envelope as manual playback, plus a plain-language description of the mix. */
public record AutomaticPlaylistResponse(PlaylistResponse playlist, List<PlaylistVideoResponse> videos,
                                        boolean personalized, String description, Instant generatedAt,
                                        int page, int size, boolean hasMore) { }
