package ITubeBackend.engagement.memory;

import ITubeBackend.engagement.SavedVideoStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Same contract as ScyllaDB, used only by the memory/test profile. */
public class InMemorySavedVideoStore implements SavedVideoStore, InMemoryEngagementStores.Resettable {
    private final Map<Long, Map<Long, SavedVideo>> byUser = new HashMap<>();

    @Override
    public synchronized boolean saveIfAbsent(long userId, long videoId, Instant savedAt) {
        return byUser.computeIfAbsent(userId, ignored -> new HashMap<>())
                .putIfAbsent(videoId, new SavedVideo(userId, videoId, savedAt)) == null;
    }

    @Override
    public synchronized boolean isSaved(long userId, long videoId) {
        return byUser.getOrDefault(userId, Map.of()).containsKey(videoId);
    }

    @Override
    public synchronized void remove(long userId, long videoId) {
        Map<Long, SavedVideo> saved = byUser.get(userId);
        if (saved != null) saved.remove(videoId);
    }

    @Override
    public synchronized void clear(long userId) { byUser.remove(userId); }

    @Override
    public synchronized Stream<SavedVideo> newestFirst(long userId) {
        return byUser.getOrDefault(userId, Map.of()).values().stream()
                .sorted(Comparator.comparing(SavedVideo::savedAt).thenComparingLong(SavedVideo::videoId).reversed())
                .toList().stream();
    }

    @Override
    public synchronized void reset() { byUser.clear(); }
}
