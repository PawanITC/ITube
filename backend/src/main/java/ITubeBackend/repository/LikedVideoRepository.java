package ITubeBackend.repository;

import ITubeBackend.entities.LikedVideoEntity;
import ITubeBackend.dto.likedvideos.LikedVideoRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.time.Instant;

public interface LikedVideoRepository extends JpaRepository<LikedVideoEntity, Long> {

    // Let the database handle repeated or concurrent requests without duplicate likes.
    @Modifying
    @Query(value = "insert into liked_video (user_id, video_id, liked_at) " +
            "values (:userId, :videoId, :likedAt) on conflict do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("videoId") Long videoId,
                       @Param("likedAt") Instant likedAt);

    // A previously liked video may become private: only its channel owner can still see it.
    @Query("select new ITubeBackend.dto.likedvideos.LikedVideoRow(" +
            "v.videoId, c.channelId, v.title, c.channelName, v.duration, " +
            "case when v.thumbnail is null then false else true end, " +
            "v.processingStatus, v.visibility, v.requiredTierLevel, l.likedAt) " +
            "from LikedVideoEntity l join l.video v join v.channel c " +
            "where l.user.userId = :userId " +
            "and (v.visibility <> ITubeBackend.entities.VideoVisibility.PRIVATE or c.user.userId = :userId) " +
            "order by l.likedAt desc, l.likedVideoId desc")
    List<LikedVideoRow> findVisibleLikesByUserId(
            @Param("userId") Long userId, Pageable pageable);

    boolean existsByUser_UserIdAndVideo_VideoId(Long userId, Long videoId);

    @Modifying
    @Query(value = "delete from liked_video where user_id = :userId and video_id = :videoId", nativeQuery = true)
    int deleteForUserAndVideo(@Param("userId") Long userId, @Param("videoId") Long videoId);
}
