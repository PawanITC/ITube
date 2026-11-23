package ITubeBackend.engagement;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Short-lived, owner-scoped playback snapshots, separate from manually edited playlists. */
public interface AutomaticPlaylistStore {
    int TTL_SECONDS = 24 * 60 * 60;
    void save(Snapshot snapshot);
    Optional<Snapshot> find(long userId, UUID id);

    record Snapshot(UUID id, long userId, Instant generatedAt, boolean personalized, List<Long> videoIds) {
        public Snapshot { videoIds = List.copyOf(videoIds); }
    }
}
