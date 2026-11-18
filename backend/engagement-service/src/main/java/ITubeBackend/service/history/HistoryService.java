package ITubeBackend.service.history;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.client.DownstreamUnavailableException;
import ITubeBackend.dto.history.HistoryResponse;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.engagement.WatchHistoryStore;
import ITubeBackend.engagement.WatchHistoryStore.HistoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entries live in ScyllaDB (engagement); video details come from catalog-service in one batched, cached call
 * per page. Missing, deleted or private videos keep a removable History row but expose no playable metadata.
 * <p>
 * Graceful degradation: if the catalog is down, History still loads (and recording a view still works) - the
 * rows just come back without video details, exactly like rows for deleted videos. The user's own data never
 * becomes unavailable because another service is.
 */
@Service
public class HistoryService {
    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private final WatchHistoryStore historyStore;
    private final CatalogClient catalog;
    private final Clock clock;

    public HistoryService(WatchHistoryStore historyStore, CatalogClient catalog, Clock clock) {
        this.historyStore = historyStore;
        this.catalog = catalog;
        this.clock = clock;
    }

    public void clearHistory(Long userId) {
        historyStore.clear(userId);
    }

    /** False if the id is malformed or isn't one of this user's entries. */
    public boolean deleteHistory(String id, Long userId) {
        UUID entryId = parseId(id);
        if (historyStore.find(userId, entryId).isEmpty()) return false;
        historyStore.delete(userId, entryId);
        return true;
    }

    public HistoryResponse addHistory(Long userId, Long videoId) {
        HistoryEntry entry = historyStore.add(userId, videoId, clock.instant());
        return toResponse(entry, videoSummaries(userId, List.of(entry)).get(entry.videoId()));
    }

    public List<HistoryResponse> getHistory(Long userId, int page, int size) {
        List<HistoryEntry> entries = historyStore.page(userId, page * size, size);
        Map<Long, VideoSummaryDto> videos = videoSummaries(userId, entries);
        return entries.stream().map(entry -> toResponse(entry, videos.get(entry.videoId()))).toList();
    }

    private Map<Long, VideoSummaryDto> videoSummaries(Long userId, List<HistoryEntry> entries) {
        if (entries.isEmpty()) return Map.of();
        List<Long> ids = entries.stream().map(HistoryEntry::videoId).distinct().toList();
        try {
            return catalog.visibleVideoSummaries(ids, userId);
        } catch (DownstreamUnavailableException exception) {
            log.warn("Catalog unavailable; returning {} history entries without video details", entries.size());
            return Map.of();
        }
    }

    private static UUID parseId(String id) {
        try {
            UUID uuid = UUID.fromString(id);
            if (uuid.version() != 1) throw new IllegalArgumentException("Not a history entry id");
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid history entry id");
        }
    }

    private static HistoryResponse toResponse(HistoryEntry entry, VideoSummaryDto video) {
        return new HistoryResponse(entry.id(), entry.videoId(), entry.watchedAt(), video);
    }
}
