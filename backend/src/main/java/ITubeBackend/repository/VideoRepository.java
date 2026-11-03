package ITubeBackend.repository;

import ITubeBackend.entities.VideoEntity;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoAssetDto;
import ITubeBackend.dto.video.VideoStreamInfo;
import ITubeBackend.dto.video.VideoSummaryDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VideoRepository extends JpaRepository<VideoEntity, Long> {
    /** Feed: public and members-only videos (members-only are shown locked to non-members). */
    @Query("select new ITubeBackend.dto.video.VideoSummaryDto(v.videoId, v.channel.channelId, v.title, v.channel.channelName, v.duration, case when v.thumbnail is null then false else true end, v.processingStatus, v.visibility, v.requiredTierLevel) from VideoEntity v where v.visibility in (ITubeBackend.entities.VideoVisibility.PUBLIC, ITubeBackend.entities.VideoVisibility.MEMBERS_ONLY) order by v.videoId desc")
    List<VideoSummaryDto> findVideoSummaries();

    /** includeHidden lets the channel owner see their unlisted and private videos. */
    @Query("select new ITubeBackend.dto.video.VideoSummaryDto(v.videoId, v.channel.channelId, v.title, v.channel.channelName, v.duration, case when v.thumbnail is null then false else true end, v.processingStatus, v.visibility, v.requiredTierLevel) " +
            "from VideoEntity v where v.channel.channelId = :channelId " +
            "and (:includeHidden = true or v.visibility in (ITubeBackend.entities.VideoVisibility.PUBLIC, ITubeBackend.entities.VideoVisibility.MEMBERS_ONLY)) order by v.videoId desc")
    List<VideoSummaryDto> findVideoSummariesByChannelId(@Param("channelId") Long channelId,
                                                        @Param("includeHidden") boolean includeHidden);

    @Query("select new ITubeBackend.dto.video.VideoAccessDto(v.videoId, v.channel.channelId, v.channel.user.userId, v.visibility, v.requiredTierLevel, v.duration) " +
            "from VideoEntity v where v.videoId = :videoId")
    Optional<VideoAccessDto> findAccess(@Param("videoId") Long videoId);

    @Query("select new ITubeBackend.dto.video.VideoAssetDto(v.video, v.videoContentType) from VideoEntity v where v.videoId = :videoId")
    Optional<VideoAssetDto> findVideoAsset(@Param("videoId") Long videoId);

    @Query("select new ITubeBackend.dto.video.VideoAssetDto(v.thumbnail, v.thumbnailContentType) from VideoEntity v where v.videoId = :videoId and v.thumbnail is not null")
    Optional<VideoAssetDto> findThumbnailAsset(@Param("videoId") Long videoId);

    @Query(value = "select quality from video_rendition where video_id = :videoId order by case quality when '360p' then 1 when '720p' then 2 else 3 end", nativeQuery = true)
    List<String> findRenditionQualities(@Param("videoId") Long videoId);

    @Query(value = "select content, content_type as \"contentType\" from video_rendition where video_id = :videoId and quality = :quality", nativeQuery = true)
    Optional<VideoAssetDto> findRendition(@Param("videoId") Long videoId, @Param("quality") String quality);

    // Range reads: octet_length and substring on an uncompressed TOAST value only touch the bytes needed, not the whole video
    @Query(value = "select cast(octet_length(video) as bigint) as \"length\", video_content_type as \"contentType\" from video where video_id = :videoId", nativeQuery = true)
    Optional<VideoStreamInfo> findVideoStreamInfo(@Param("videoId") Long videoId);

    @Query(value = "select substring(video from cast(:start as integer) for cast(:length as integer)) as content, video_content_type as \"contentType\" from video where video_id = :videoId", nativeQuery = true)
    Optional<VideoAssetDto> findVideoChunk(@Param("videoId") Long videoId, @Param("start") int start, @Param("length") int length);

    @Query(value = "select cast(octet_length(content) as bigint) as \"length\", content_type as \"contentType\" from video_rendition where video_id = :videoId and quality = :quality", nativeQuery = true)
    Optional<VideoStreamInfo> findRenditionStreamInfo(@Param("videoId") Long videoId, @Param("quality") String quality);

    @Query(value = "select substring(content from cast(:start as integer) for cast(:length as integer)) as content, content_type as \"contentType\" from video_rendition where video_id = :videoId and quality = :quality", nativeQuery = true)
    Optional<VideoAssetDto> findRenditionChunk(@Param("videoId") Long videoId, @Param("quality") String quality, @Param("start") int start, @Param("length") int length);

    @Modifying
    @Query(value = "insert into video_rendition(video_id, quality, content_type, content) values (:videoId, :quality, 'video/mp4', :bytes) on conflict (video_id, quality) do update set content = excluded.content", nativeQuery = true)
    void saveRendition(@Param("videoId") Long videoId, @Param("quality") String quality, @Param("bytes") byte[] bytes);
}
