package History;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface HistoryRepository extends JpaRepository<WatchHistory, Long> {

    List<WatchHistory> findByUserIdOrderByWatchedAtDescIdDesc(String userId, Pageable pageable);

    Optional<WatchHistory> findByIdAndUserId(Long id, String userId);

    void deleteByUserId(String userId);
}
