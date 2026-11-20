package ITubeBackend.repository;

import ITubeBackend.dto.video.AdCandidate;
import ITubeBackend.dto.video.RenditionRef;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoMediaDto;
import ITubeBackend.dto.video.VideoRow;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.entities.VideoEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Every query here runs on ONE catalog shard (chosen by ITubeBackend.sharding.Shards before calling). Queries that
 * need all videos (the feed) are run on each shard and merged by the caller.
 */
public interface VideoRepository extends JpaRepository<VideoEntity, Long> {
    /**
     * Feed page from this shard: public and members-only videos (members-only are shown locked), newest first.
     * Ads are not listed; they only play inside other videos.
     */
    @Query("select new ITubeBackend.dto.video.VideoRow(v.videoId, c.channelId, v.title, c.channelName, v.duration, case when v.thumbnailKey is null then false else true end, v.processingStatus, v.visibility, v.requiredTierLevel, c.ownerUserId, v.createdAt) from VideoEntity v join v.channel c where v.visibility in (ITubeBackend.entities.VideoVisibility.PUBLIC, ITubeBackend.entities.VideoVisibility.MEMBERS_ONLY) " +
            "and v.videoType = ITubeBackend.entities.VideoType.VIDEO " +
            "order by v.createdAt desc, v.videoId desc")
    List<VideoRow> findFeed(Limit limit);

    /** includeHidden lets the channel owner see their unlisted and private videos, and their ads. */
    @Query("select new ITubeBackend.dto.video.VideoSummaryDto(v.videoId, v.channel.channelId, v.title, v.channel.channelName, v.duration, case when v.thumbnailKey is null then false else true end, v.processingStatus, v.visibility, v.requiredTierLevel) " +
            "from VideoEntity v where v.channel.channelId = :channelId " +
            "and (:includeHidden = true or (v.visibility in (ITubeBackend.entities.VideoVisibility.PUBLIC, ITubeBackend.entities.VideoVisibility.MEMBERS_ONLY) " +
            "and v.videoType = ITubeBackend.entities.VideoType.VIDEO)) order by v.videoId desc")
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
     * Batch lookup for other services (History, Liked videos), whose rows only hold video ids. Returns every match
     * with its owner; the caller applies the visibility rule per viewer (InternalVideoSummary.visibleTo), so one
     * cached copy per video serves every viewer.
     */
    @Query("select new ITubeBackend.dto.video.VideoRow(v.videoId, c.channelId, v.title, c.channelName, v.duration, case when v.thumbnailKey is null then false else true end, v.processingStatus, v.visibility, v.requiredTierLevel, c.ownerUserId, v.createdAt) from VideoEntity v join v.channel c where v.videoId in :videoIds")
    List<VideoRow> findRows(@Param("videoIds") Collection<Long> videoIds);

    /** Public ads on this shard that can play inside other videos, newest first. */
    @Query("select new ITubeBackend.dto.video.AdCandidate(v.videoId, v.title, c.channelName, v.duration) from VideoEntity v join v.channel c " +
            "where v.videoType = ITubeBackend.entities.VideoType.AD and v.visibility = ITubeBackend.entities.VideoVisibility.PUBLIC " +
            "and v.processingStatus <> 'FAILED' order by v.createdAt desc, v.videoId desc")
    List<AdCandidate> findAdCandidates(Limit limit);

    /** How many ads play in the video; 0 if its ads are off. */
    @Query("select case when v.adsEnabled = true then v.adCount else 0 end from VideoEntity v where v.videoId = :videoId")
    Optional<Integer> findAdCount(@Param("videoId") Long videoId);

    /** Videos stuck in PROCESSING whose transcode was last requested before {@code cutoff} (TranscodeSweeper). */
    @Query("select v from VideoEntity v where v.processingStatus = 'PROCESSING' and " +
            "(v.transcodeRequestedAt is null or v.transcodeRequestedAt < :cutoff) order by v.videoId")
    List<VideoEntity> findStuckProcessing(@Param("cutoff") OffsetDateTime cutoff, Limit limit);

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
