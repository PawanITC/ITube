package ITubeBackend.repository;

import ITubeBackend.entities.WatchHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HistoryRepository extends JpaRepository<WatchHistory, Long> {

    List<WatchHistory> findByUserIdOrderByWatchedAtDescIdDesc(String userId, Pageable pageable);

    Optional<WatchHistory> findByIdAndUserId(Long id, String userId);

    void deleteByUserId(String userId);

    @Modifying
    @Query("delete from WatchHistory h where h.videoId = :videoId")
    void deleteByVideoId(@Param("videoId") Long videoId);
}
