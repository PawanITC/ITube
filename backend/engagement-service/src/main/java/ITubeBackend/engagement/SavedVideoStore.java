package ITubeBackend.engagement;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

/** Personal Watch later list. Saving twice keeps the original date and creates no duplicate. */
public interface SavedVideoStore {
    default Optional<SavedVideo> find(long userId, long videoId) {
        try (var rows = newestFirst(userId)) { return rows.filter(row -> row.videoId() == videoId).findFirst(); }
    }
    boolean saveIfAbsent(long userId, long videoId, Instant savedAt);
    boolean isSaved(long userId, long videoId);
    void remove(long userId, long videoId);
    void clear(long userId);

    /** Newest saved first; ties use descending video id. Close the driver-backed stream. */
    Stream<SavedVideo> newestFirst(long userId);

    record SavedVideo(long userId, long videoId, Instant savedAt) { }
}
