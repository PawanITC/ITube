package ITubeBackend.dto.playlists;

import java.time.Instant;
import java.util.UUID;

public record PlaylistResponse(UUID id, String title, Instant createdAt, Instant updatedAt,
                               int videoCount, UUID version) { }
