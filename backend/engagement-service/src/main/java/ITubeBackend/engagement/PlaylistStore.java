package ITubeBackend.engagement;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Private playlists, partitioned by their owner. A version protects edits across service instances. */
public interface PlaylistStore {
    boolean create(Playlist playlist);
    Optional<Playlist> find(long userId, UUID playlistId);
    Stream<Playlist> newestFirst(long userId);
    boolean replace(Playlist before, Playlist after);
    /** Removes metadata/order but retains a minimal id marker to reject late duplicate creates. */
    boolean delete(Playlist playlist);

    record Item(long videoId, Instant addedAt) { }

    record Playlist(UUID id, long userId, String title, Instant createdAt, Instant updatedAt,
                    UUID version, List<Item> items) {
        public Playlist { items = List.copyOf(items); }
    }
}
