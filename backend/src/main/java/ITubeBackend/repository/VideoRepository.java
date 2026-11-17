package ITubeBackend.repository;

import ITubeBackend.dto.video.RenditionRef;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoMediaDto;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.entities.VideoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VideoRepository extends JpaRepository<VideoEntity, Long> {
    /** Feed: public and members-only videos (members-only are shown locked to non-members). */
    @Query("select new ITubeBackend.dto.video.VideoSummaryDto(v.videoId, v.channel.channelId, v.title, v.channel.channelName, v.duration, case when v.thumbnailKey is null then false else true end, v.processingStatus, v.visibility, v.requiredTierLevel) from VideoEntity v where v.visibility in (ITubeBackend.entities.VideoVisibility.PUBLIC, ITubeBackend.entities.VideoVisibility.MEMBERS_ONLY) order by v.videoId desc")
    List<VideoSummaryDto> findVideoSummaries();

    /** includeHidden lets the channel owner see their unlisted and private videos. */
    @Query("select new ITubeBackend.dto.video.VideoSummaryDto(v.videoId, v.channel.channelId, v.title, v.channel.channelName, v.duration, case when v.thumbnailKey is null then false else true end, v.processingStatus, v.visibility, v.requiredTierLevel) " +
            "from VideoEntity v where v.channel.channelId = :channelId " +
            "and (:includeHidden = true or v.visibility in (ITubeBackend.entities.VideoVisibility.PUBLIC, ITubeBackend.entities.VideoVisibility.MEMBERS_ONLY)) order by v.videoId desc")
    List<VideoSummaryDto> findVideoSummariesByChannelId(@Param("channelId") Long channelId,
                                                        @Param("includeHidden") boolean includeHidden);

    /** Every video on channels owned by the user, including unlisted and private ones. */
    @Query("select new ITubeBackend.dto.video.VideoSummaryDto(v.videoId, v.channel.channelId, v.title, v.channel.channelName, v.duration, case when v.thumbnailKey is null then false else true end, v.processingStatus, v.visibility, v.requiredTierLevel) " +
            "from VideoEntity v where v.channel.ownerUserId = :userId order by v.videoId desc")
    List<VideoSummaryDto> findVideoSummariesByOwner(@Param("userId") Long userId);

    @Query("select new ITubeBackend.dto.video.VideoSummaryDto(v.videoId, v.channel.channelId, v.title, v.channel.channelName, v.duration, case when v.thumbnailKey is null then false else true end, v.processingStatus, v.visibility, v.requiredTierLevel) " +
            "from VideoEntity v where v.videoId = :videoId and v.channel.ownerUserId = :userId")
    Optional<VideoSummaryDto> findVideoSummaryForOwner(@Param("videoId") Long videoId, @Param("userId") Long userId);

    /**
     * Batch lookup used by History and Liked videos, whose rows live in ScyllaDB and only hold video ids.
     * Private videos are only returned to their channel owner; ids that don't match are simply absent.
     */
    @Query("select new ITubeBackend.dto.video.VideoSummaryDto(v.videoId, c.channelId, v.title, c.channelName, v.duration, " +
            "case when v.thumbnailKey is null then false else true end, v.processingStatus, v.visibility, v.requiredTierLevel) " +
            "from VideoEntity v join v.channel c where v.videoId in :videoIds " +
            "and (v.visibility <> ITubeBackend.entities.VideoVisibility.PRIVATE or c.ownerUserId = :userId)")
    List<VideoSummaryDto> findVisibleVideoSummaries(@Param("videoIds") Collection<Long> videoIds,
                                                    @Param("userId") Long userId);

    // Renditions go with it via ON DELETE CASCADE; the stored files are removed by the video.deleted consumer
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from VideoEntity v where v.videoId = :videoId")
    int deleteVideoById(@Param("videoId") Long videoId);

    @Query("select new ITubeBackend.dto.video.VideoAccessDto(v.videoId, v.channel.channelId, v.channel.ownerUserId, v.visibility, v.requiredTierLevel, v.duration) " +
            "from VideoEntity v where v.videoId = :videoId")
    Optional<VideoAccessDto> findAccess(@Param("videoId") Long videoId);

    @Query("select new ITubeBackend.dto.video.VideoMediaDto(v.videoKey, v.videoContentType, v.thumbnailKey, v.thumbnailContentType) " +
            "from VideoEntity v where v.videoId = :videoId")
    Optional<VideoMediaDto> findMedia(@Param("videoId") Long videoId);

    @Query(value = "select quality from catalog.video_rendition where video_id = :videoId and storage_key is not null " +
            "order by case quality when '360p' then 1 when '720p' then 2 else 3 end", nativeQuery = true)
    List<String> findRenditionQualities(@Param("videoId") Long videoId);

    @Query(value = "select storage_key as \"storageKey\", content_type as \"contentType\" from catalog.video_rendition " +
            "where video_id = :videoId and quality = :quality and storage_key is not null", nativeQuery = true)
    Optional<RenditionRef> findRendition(@Param("videoId") Long videoId, @Param("quality") String quality);

    @Query(value = "select storage_key from catalog.video_rendition where video_id = :videoId and storage_key is not null",
            nativeQuery = true)
    List<String> findRenditionKeys(@Param("videoId") Long videoId);

    @Modifying
    @Query(value = "insert into catalog.video_rendition (video_id, quality, content_type, storage_key, size_bytes) " +
            "values (:videoId, :quality, :contentType, :storageKey, :sizeBytes) " +
            "on conflict (video_id, quality) do update set content_type = excluded.content_type, " +
            "storage_key = excluded.storage_key, size_bytes = excluded.size_bytes", nativeQuery = true)
    void saveRendition(@Param("videoId") Long videoId, @Param("quality") String quality,
                       @Param("contentType") String contentType, @Param("storageKey") String storageKey,
                       @Param("sizeBytes") long sizeBytes);
}
