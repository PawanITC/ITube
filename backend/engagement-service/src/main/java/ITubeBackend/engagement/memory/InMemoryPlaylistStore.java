package ITubeBackend.engagement.memory;

import ITubeBackend.engagement.PlaylistStore;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Test stand-in with the same version and ordering rules as ScyllaDB. */
public class InMemoryPlaylistStore implements PlaylistStore, InMemoryEngagementStores.Resettable {
    private final Map<Long, Map<UUID, Playlist>> byUser = new HashMap<>();

    @Override
    public synchronized boolean create(Playlist playlist) {
        return byUser.computeIfAbsent(playlist.userId(), ignored -> new HashMap<>())
                .putIfAbsent(playlist.id(), playlist) == null;
    }

    @Override
    public synchronized Optional<Playlist> find(long userId, UUID playlistId) {
        return Optional.ofNullable(byUser.getOrDefault(userId, Map.of()).get(playlistId));
    }

    @Override
    public synchronized Stream<Playlist> newestFirst(long userId) {
        // CQL timeuuid compares timestamps first, then the remaining UUID bits.
        return byUser.getOrDefault(userId, Map.of()).values().stream().sorted(
                Comparator.comparingLong((Playlist value) -> value.id().timestamp())
                        .thenComparing(Playlist::id).reversed()).toList().stream();
    }

    @Override
    public synchronized boolean replace(Playlist before, Playlist after) {
        var current = find(before.userId(), before.id());
        if (current.isEmpty() || !current.get().version().equals(before.version())) return false;
        byUser.get(before.userId()).put(before.id(), after);
        return true;
    }

    @Override
    public synchronized boolean delete(Playlist playlist) {
        var current = find(playlist.userId(), playlist.id());
        if (current.isEmpty() || !current.get().version().equals(playlist.version())) return false;
        byUser.get(playlist.userId()).remove(playlist.id());
        return true;
    }

    @Override
    public synchronized void reset() { byUser.clear(); }
}
