package ITubeBackend.engagement.memory;

import ITubeBackend.engagement.AutomaticPlaylistStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public class InMemoryAutomaticPlaylistStore implements AutomaticPlaylistStore {
    private record Key(long userId, UUID id) { }
    private final Cache<Key, Snapshot> snapshots = Caffeine.newBuilder().maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(TTL_SECONDS)).build();
    private final Clock clock;

    public InMemoryAutomaticPlaylistStore(Clock clock) { this.clock = clock; }

    @Override
    public void save(Snapshot snapshot) { snapshots.put(new Key(snapshot.userId(), snapshot.id()), snapshot); }

    @Override
    public Optional<Snapshot> find(long userId, UUID id) {
        return Optional.ofNullable(snapshots.getIfPresent(new Key(userId, id)))
                .filter(value -> value.generatedAt().plusSeconds(TTL_SECONDS).isAfter(clock.instant()));
    }
}
