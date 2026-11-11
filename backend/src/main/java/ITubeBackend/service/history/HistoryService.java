package ITubeBackend.service.history;

import ITubeBackend.dto.history.HistoryResponse;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.repository.HistoryRepository;
import ITubeBackend.entities.WatchHistory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.Instant;

@Service
public class HistoryService {

    private final HistoryRepository historyRepository;

    public HistoryService(HistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    @Transactional
    public void clearHistory(String userId) {
        historyRepository.deleteByUserId(userId);
    }

    @Transactional
    public boolean deleteHistory(Long id, String userId) {
        Optional<WatchHistory> entry = historyRepository.findByIdAndUserId(id, userId);

        if (entry.isEmpty()) {
            return false;
        }

        historyRepository.delete(entry.get());
        return true;
    }

    @Transactional
    public HistoryResponse addHistory(String userId, Long videoId) {
        WatchHistory entry = new WatchHistory(userId, videoId, Instant.now());
        WatchHistory savedEntry = historyRepository.save(entry);

        return toResponse(savedEntry, videoSummaries(userId, List.of(savedEntry)).get(savedEntry.getVideoId()));
    }

    @Transactional(readOnly = true)
    public List<HistoryResponse> getHistory(String userId, int page, int size) {
        List<WatchHistory> historyEntries =
                historyRepository.findByUserIdOrderByWatchedAtDescIdDesc(
                        userId, PageRequest.of(page, size));

        Map<Long, VideoSummaryDto> videos = videoSummaries(userId, historyEntries);
        List<HistoryResponse> responses = new ArrayList<>();

        for (WatchHistory entry : historyEntries) {
            responses.add(toResponse(entry, videos.get(entry.getVideoId())));
        }

        return responses;
    }

    private Map<Long, VideoSummaryDto> videoSummaries(String userId, List<WatchHistory> entries) {
        if (entries.isEmpty()) return Map.of();
        List<Long> ids = entries.stream().map(WatchHistory::getVideoId).distinct().toList();
        return historyRepository.findVisibleVideoSummaries(ids, userId).stream()
                .collect(Collectors.toMap(VideoSummaryDto::videoId, Function.identity()));
    }

    private HistoryResponse toResponse(WatchHistory entry, VideoSummaryDto video) {
        // Missing/deleted/private videos retain a removable History row but expose no playable metadata.
        return new HistoryResponse(entry.getId(), entry.getVideoId(), entry.getWatchedAt(), video);
    }
}
