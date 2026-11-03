package ITubeBackend.service.history;

import ITubeBackend.dto.history.HistoryResponse;
import ITubeBackend.repository.HistoryRepository;
import ITubeBackend.entities.WatchHistory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
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

        return toResponse(savedEntry);
    }

    @Transactional(readOnly = true)
    public List<HistoryResponse> getHistory(String userId, int page, int size) {
        List<WatchHistory> historyEntries =
                historyRepository.findByUserIdOrderByWatchedAtDescIdDesc(
                        userId, PageRequest.of(page, size));

        List<HistoryResponse> responses = new ArrayList<>();

        for (WatchHistory entry : historyEntries) {
            responses.add(toResponse(entry));
        }

        return responses;
    }

    private HistoryResponse toResponse(WatchHistory entry) {
        return new HistoryResponse(entry.getId(), entry.getVideoId(), entry.getWatchedAt());
    }
}
