package ITubeBackend.service.history;

import ITubeBackend.dto.history.HistoryResponse;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.engagement.WatchHistoryStore;
import ITubeBackend.engagement.WatchHistoryStore.HistoryEntry;
import ITubeBackend.repository.VideoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Entries live in ScyllaDB (engagement); video details come from the catalog in one batched query per page.
 * Missing, deleted or private videos keep a removable History row but expose no playable metadata.
 */
@Service
public class HistoryService {

    private final WatchHistoryStore historyStore;
    private final VideoRepository videoRepository;
    private final Clock clock;

    public HistoryService(WatchHistoryStore historyStore, VideoRepository videoRepository, Clock clock) {
        this.historyStore = historyStore;
        this.videoRepository = videoRepository;
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
        return videoRepository.findVisibleVideoSummaries(ids, userId).stream()
                .collect(Collectors.toMap(VideoSummaryDto::videoId, Function.identity()));
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
