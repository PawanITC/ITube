package ITubeBackend.dto.playlists;

import java.util.List;

public record PlaylistDetailResponse(PlaylistResponse playlist, List<PlaylistVideoResponse> videos,
                                     int page, int size, boolean hasMore) { }
