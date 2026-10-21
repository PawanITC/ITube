package ITubeBackend.repository;

import ITubeBackend.entities.VideoEntity;
import ITubeBackend.dto.video.VideoAssetDto;
import ITubeBackend.dto.video.VideoSummaryDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VideoRepository extends JpaRepository<VideoEntity, Long> {
    @Query("select new ITubeBackend.dto.video.VideoSummaryDto(v.videoId, v.channel.channelId, v.title, v.channel.channelName, v.duration, case when v.thumbnail is null then false else true end) from VideoEntity v order by v.videoId desc")
    List<VideoSummaryDto> findVideoSummaries();

    @Query("select new ITubeBackend.dto.video.VideoSummaryDto(v.videoId, v.channel.channelId, v.title, v.channel.channelName, v.duration, case when v.thumbnail is null then false else true end) " +
            "from VideoEntity v where v.channel.channelId = :channelId order by v.videoId desc")
    List<VideoSummaryDto> findVideoSummariesByChannelId(@Param("channelId") Long channelId);

    @Query("select new ITubeBackend.dto.video.VideoAssetDto(v.video, v.videoContentType) from VideoEntity v where v.videoId = :videoId")
    Optional<VideoAssetDto> findVideoAsset(@Param("videoId") Long videoId);

    @Query("select new ITubeBackend.dto.video.VideoAssetDto(v.thumbnail, v.thumbnailContentType) from VideoEntity v where v.videoId = :videoId and v.thumbnail is not null")
    Optional<VideoAssetDto> findThumbnailAsset(@Param("videoId") Long videoId);
}
