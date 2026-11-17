package ITubeBackend.engagement;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-user watch history, newest first. Backed by ScyllaDB in production
 * (partition = user, clustering = time-based entry id), so writes scale with nodes.
 */
public interface WatchHistoryStore {
    HistoryEntry add(long userId, long videoId, Instant watchedAt);

    /** Writes an entry with a known id (an upsert), e.g. when copying old data. */
    void put(HistoryEntry entry);

    /** Skips {@code offset} entries; cost grows with the offset, so keep pages near the start. */
    List<HistoryEntry> page(long userId, int offset, int limit);

    Optional<HistoryEntry> find(long userId, UUID entryId);

    void delete(long userId, UUID entryId);

    void clear(long userId);

    record HistoryEntry(UUID id, long userId, long videoId, Instant watchedAt) {
    }
}
