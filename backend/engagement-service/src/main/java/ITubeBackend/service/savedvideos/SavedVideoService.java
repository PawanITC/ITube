package ITubeBackend.service.savedvideos;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.client.DownstreamUnavailableException;
import ITubeBackend.dto.savedvideos.SavedVideoResponse;
import ITubeBackend.dto.savedvideos.SavedVideoStatusResponse;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.engagement.SavedVideoStore;
import ITubeBackend.engagement.SavedVideoStore.SavedVideo;
import ITubeBackend.entities.VideoVisibility;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/** Saved ids live in ScyllaDB; catalog-service supplies current titles, thumbnails and visibility. */
@Service
public class SavedVideoService {
    private final SavedVideoStore store;
    private final CatalogClient catalog;
    private final Clock clock;

    public SavedVideoService(SavedVideoStore store, CatalogClient catalog, Clock clock) {
        this.store = store;
        this.catalog = catalog;
        this.clock = clock;
    }

    public void save(Long userId, Long videoId) {
        requireUser(userId);
        requireVideoId(videoId);
        var video = catalog.videoAccess(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
        if (video.visibility() == VideoVisibility.PRIVATE && !userId.equals(video.ownerUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        store.saveIfAbsent(userId, videoId, clock.instant());
    }

    public void remove(Long userId, Long videoId) {
        requireUser(userId);
        requireVideoId(videoId);
        // Removing a saved item never depends on whether the video still exists or catalog is online.
        store.remove(userId, videoId);
    }

    public void clear(Long userId) {
        requireUser(userId);
        store.clear(userId);
    }

    public SavedVideoStatusResponse status(Long userId, Long videoId) {
        requireUser(userId);
        requireVideoId(videoId);
        return new SavedVideoStatusResponse(videoId, store.isSaved(userId, videoId));
    }

    public List<SavedVideoResponse> list(Long userId, int page, int size) {
        requireUser(userId);
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be nonnegative and size must be 1 to 100");
        }
        List<SavedVideo> entries;
        try (var saved = store.newestFirst(userId)) {
            entries = saved.skip((long) page * size).limit(size).toList();
        }
        if (entries.isEmpty()) return List.of();
        Map<Long, VideoSummaryDto> videos;
        try {
            videos = catalog.visibleVideoSummaries(entries.stream().map(SavedVideo::videoId).toList(), userId);
        } catch (DownstreamUnavailableException exception) {
            // Keep the user's saved list available, with removable placeholders, just like History.
            videos = Map.of();
        }
        Map<Long, VideoSummaryDto> visible = videos;
        return entries.stream().map(entry -> new SavedVideoResponse(
                entry.videoId(), entry.savedAt(), visible.get(entry.videoId()))).toList();
    }

    private static void requireVideoId(Long videoId) {
        if (videoId == null || videoId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A positive video ID is required");
        }
    }

    private static void requireUser(Long userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
    }
}
