package ITubeBackend.repository;

import ITubeBackend.entities.WatchHistory;
import ITubeBackend.dto.video.VideoSummaryDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface HistoryRepository extends JpaRepository<WatchHistory, Long> {

    List<WatchHistory> findByUserIdOrderByWatchedAtDescIdDesc(String userId, Pageable pageable);

    Optional<WatchHistory> findByIdAndUserId(Long id, String userId);

    void deleteByUserId(String userId);

    // Load the current metadata for one History page without fetching large video/thumbnail bytes.
    // History stores user IDs as strings; casting keeps the existing table/service contract intact.
    @Query("select new ITubeBackend.dto.video.VideoSummaryDto(" +
            "v.videoId, c.channelId, v.title, c.channelName, v.duration, " +
            "case when v.thumbnail is null then false else true end, " +
            "v.processingStatus, v.visibility, v.requiredTierLevel) " +
            "from VideoEntity v join v.channel c where v.videoId in :videoIds " +
            "and (v.visibility <> ITubeBackend.entities.VideoVisibility.PRIVATE " +
            "or cast(c.user.userId as string) = :userId)")
    List<VideoSummaryDto> findVisibleVideoSummaries(@Param("videoIds") Collection<Long> videoIds,
                                                  @Param("userId") String userId);

    @Modifying
    @Query("delete from WatchHistory h where h.videoId = :videoId")
    void deleteByVideoId(@Param("videoId") Long videoId);
}
