package ITubeBackend.engagement;

import java.time.Instant;
import java.util.stream.Stream;

/** Personal Watch later list. Saving twice keeps the original date and creates no duplicate. */
public interface SavedVideoStore {
    boolean saveIfAbsent(long userId, long videoId, Instant savedAt);
    boolean isSaved(long userId, long videoId);
    void remove(long userId, long videoId);
    void clear(long userId);

    /** Newest saved first; ties use descending video id. Close the driver-backed stream. */
    Stream<SavedVideo> newestFirst(long userId);

    record SavedVideo(long userId, long videoId, Instant savedAt) { }
}
