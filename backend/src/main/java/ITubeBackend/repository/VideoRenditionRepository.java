package ITubeBackend.repository;

// REVIEW

import ITubeBackend.dto.video.VideoAssetDto;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

// Renditions are written by the video-rendition-sink connector; there is no entity for video_rendition, so these are native queries
public interface VideoRenditionRepository {
    @Query(value = "select quality from video_rendition where video_id = :videoId " +
            "order by case quality when '360p' then 1 when '720p' then 2 else 3 end", nativeQuery = true)
    List<String> findRenditionQualities(@Param("videoId") Long videoId);

    @Query(value = "select content as content, content_type as \"contentType\" from video_rendition " +
            "where video_id = :videoId and quality = :quality", nativeQuery = true)
    Optional<VideoAssetDto> findRendition(@Param("videoId") Long videoId, @Param("quality") String quality);
}
