package ITubeBackend.service.video;

import ITubeBackend.cache.CatalogCaches;
import ITubeBackend.cdn.MediaCdn;
import ITubeBackend.common.client.PaymentsClient;
import ITubeBackend.dto.entitlement.PlaybackDecision;
import ITubeBackend.dto.video.CreateVideo;
import ITubeBackend.dto.video.InternalVideoSummary;
import ITubeBackend.dto.video.MediaUpload;
import ITubeBackend.dto.video.PlaybackAuthorisationDto;
import ITubeBackend.dto.video.RenditionRef;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoDeletedEvent;
import ITubeBackend.dto.video.VideoMediaDto;
import ITubeBackend.dto.video.VideoRow;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.dto.video.VideoTranscodedEvent;
import ITubeBackend.dto.video.VideoUploadedEvent;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.dto.video.AdBreakDto;
import ITubeBackend.dto.video.AdCandidate;
import ITubeBackend.entities.VideoEntity;
import ITubeBackend.entities.VideoType;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.service.entitlement.PlaybackTokenService;
import ITubeBackend.sharding.IdAllocator;
import ITubeBackend.sharding.ShardIds;
import ITubeBackend.sharding.Shards;
import ITubeBackend.storage.MediaKeys;
import ITubeBackend.storage.ObjectStorage;
import ITubeBackend.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Videos: upload, metadata, visibility and playback.
 * <p>
 * <b>Sharding.</b> A video lives on its channel's shard and its id says which one, so every per-video operation
 * goes to exactly one database ({@link Shards#onShardOf}). The home feed is the only query that needs every shard:
 * it asks them in parallel and merges the newest videos ({@link #getVideos}).
 * <p>
 * <b>Caching.</b> Playback, thumbnails and other services read the same few rows over and over, so video access
 * (owner/visibility), media keys and summaries are cached in Caffeine; changes evict them on every pod
 * ({@link CatalogCaches#videoChanged}).
 * <p>
 * Transactions are opened <i>inside</i> the shard routing (TransactionTemplate), never around it, because a
 * transaction is bound to one database connection.
 */
@Service
public class VideoService {
    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    private static final long MAX_CHUNK_BYTES = 4L * 1024 * 1024;
    public static final int MAX_FEED_SIZE = 200;
    /** A video may have one ad per this many seconds of its length (10 minutes), and always at least one. */
    static final int SECONDS_PER_AD = 600;
    /** Ads only play in videos longer than this (one minute). */
    static final int MIN_SECONDS_FOR_ADS = 60;
    /** How many of each shard's newest public ads are kept to choose from. */
    static final int MAX_AD_POOL_PER_SHARD = 500;

    private final ChannelRepository channelRepository;
    private final VideoRepository videoRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentsClient payments;
    private final PlaybackTokenService playbackTokenService;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    private final TransactionTemplate transactionTemplate;
    private final Shards shards;
    private final IdAllocator ids;
    private final CatalogCaches caches;
    private final MediaCdn cdn;
    private final Clock clock;

    public VideoService(ChannelRepository channelRepository, VideoRepository videoRepository,
                        ApplicationEventPublisher eventPublisher, PaymentsClient payments,
                        PlaybackTokenService playbackTokenService, ObjectStorage storage,
                        StorageProperties storageProperties, TransactionTemplate transactionTemplate, Shards shards,
                        IdAllocator ids, CatalogCaches caches, MediaCdn cdn, Clock clock) {
        this.channelRepository = channelRepository;
        this.videoRepository = videoRepository;
        this.eventPublisher = eventPublisher;
        this.payments = payments;
        this.playbackTokenService = playbackTokenService;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.transactionTemplate = transactionTemplate;
        this.shards = shards;
        this.ids = ids;
        this.caches = caches;
        this.cdn = cdn;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ upload

    /**
     * Streams the files to object storage first, then records the video in a short transaction, so a
     * multi-minute upload never holds a database connection. If the insert fails the files are removed.
     */
    public Long saveVideo(CreateVideo createVideo) {
        if (createVideo.video() == null || createVideo.video().size() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A video file is required");
        }
        VideoVisibility visibility = validateDetails(createVideo.channelId(), createVideo.title(), createVideo.duration(),
                createVideo.visibility(), createVideo.requiredTierLevel(), createVideo.videoType());
        resolveAdCount(createVideo.videoType(), createVideo.adsEnabled(), createVideo.adCount(), createVideo.duration());

        String folder = MediaKeys.newVideoFolder();
        List<String> uploaded = new ArrayList<>();
        try {
            String videoKey = MediaKeys.source(folder);
            upload(videoKey, createVideo.video());
            uploaded.add(videoKey);
            return recordVideo(createVideo, visibility, videoKey, uploaded, null);
        } catch (RuntimeException exception) {
            uploaded.forEach(this::deleteQuietly);
            throw exception;
        }
    }

    /**
     * Records a video whose source a resumable upload already put at {@code videoKey}
     * ({@code createVideo.video()} carries only its size and type). {@code inSameTransaction} runs in the
     * transaction that inserts the video, so the upload session can be removed atomically with it. If that
     * transaction fails, only the thumbnail is removed: the source stays so the upload can be completed again.
     */
    public Long saveUploadedVideo(CreateVideo createVideo, String videoKey, Runnable inSameTransaction) {
        VideoVisibility visibility = validateDetails(createVideo.channelId(), createVideo.title(), createVideo.duration(),
                createVideo.visibility(), createVideo.requiredTierLevel(), createVideo.videoType());
        List<String> uploaded = new ArrayList<>();
        try {
            return recordVideo(createVideo, visibility, videoKey, uploaded, inSameTransaction);
        } catch (RuntimeException exception) {
            uploaded.forEach(this::deleteQuietly);
            throw exception;
        }
    }

    /** Checks the details shared by both upload paths and returns the effective visibility. */
    public VideoVisibility validateDetails(Long channelId, String title, LocalTime duration, VideoVisibility visibility,
                                           Integer requiredTierLevel) {
        return validateDetails(channelId, title, duration, visibility, requiredTierLevel, VideoType.VIDEO);
    }

    /** As above; ads must also be at most {@link VideoType#MAX_AD_DURATION} long. */
    public VideoVisibility validateDetails(Long channelId, String title, LocalTime duration, VideoVisibility visibility,
                                           Integer requiredTierLevel, VideoType videoType) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video title is required");
        }
        if (duration == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video duration is required");
        }
        if (videoType == VideoType.AD && duration.isAfter(VideoType.MAX_AD_DURATION)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ads can be at most 1 minute long");
        }
        VideoVisibility effective = visibility == null ? VideoVisibility.PUBLIC : visibility;
        validateTierLevel(effective, requiredTierLevel);
        if (channelId == null || shards.physicalOf(channelId).isEmpty()
                || !shards.onShardOf(channelId, () -> channelRepository.existsById(channelId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }
        return effective;
    }

    /** Uploads the thumbnail (adding its key to {@code uploaded}) and inserts the video row on the channel's shard. */
    private Long recordVideo(CreateVideo createVideo, VideoVisibility visibility, String videoKey, List<String> uploaded,
                             Runnable inSameTransaction) {
        String thumbnailKey = null;
        if (createVideo.thumbnail() != null && createVideo.thumbnail().size() > 0) {
            thumbnailKey = MediaKeys.thumbnail(MediaKeys.folderOf(videoKey));
            upload(thumbnailKey, createVideo.thumbnail());
            uploaded.add(thumbnailKey);
        }
        String finalThumbnailKey = thumbnailKey;
        // The video gets an id on its channel's logical shard, so channel and videos stay on one database
        int logicalShard = ShardIds.logicalShardOf(createVideo.channelId());
        return shards.onShardOf(createVideo.channelId(), () -> transactionTemplate.execute(status -> {
            ChannelEntity channel = channelRepository.findById(createVideo.channelId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
            VideoEntity video = createVideoEntity(ids.nextVideoId(logicalShard), createVideo, visibility, channel,
                    videoKey, finalThumbnailKey);
            Long videoId = videoRepository.save(video).getVideoId();
            if (inSameTransaction != null) inSameTransaction.run();
            // Sent to the transcode worker by VideoTranscodeMessaging once this transaction commits
            eventPublisher.publishEvent(new VideoUploadedEvent(videoId, videoKey));
            return videoId;
        }));
    }

    private VideoEntity createVideoEntity(Long videoId, CreateVideo createVideo, VideoVisibility visibility,
                                          ChannelEntity channel, String videoKey, String thumbnailKey) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        VideoEntity video = new VideoEntity(videoId);
        video.setVisibility(visibility);
        video.setRequiredTierLevel(visibility == VideoVisibility.MEMBERS_ONLY ? createVideo.requiredTierLevel() : null);
        video.setChannel(channel);
        video.setVideoKey(videoKey);
        video.setVideoSizeBytes(createVideo.video().size());
        video.setVideoContentType(createVideo.video().contentType());
        video.setThumbnailKey(thumbnailKey);
        video.setThumbnailContentType(thumbnailKey == null ? null : createVideo.thumbnail().contentType());
        video.setTitle(createVideo.title().trim());
        video.setDuration(createVideo.duration());
        VideoType videoType = createVideo.videoType() == null ? VideoType.VIDEO : createVideo.videoType();
        video.setVideoType(videoType);
        video.setAdsEnabled(videoType == VideoType.VIDEO && createVideo.adsEnabled());
        int adCount = resolveAdCount(videoType, createVideo.adsEnabled(), createVideo.adCount(), createVideo.duration());
        video.setAdCount(Math.max(1, adCount));
        video.setProcessingStatus("PROCESSING");
        video.setCreatedAt(now);
        video.setTranscodeRequestedAt(now);
        video.setTranscodeAttempts(1);
        return video;
    }

    // ------------------------------------------------------------------ playback

    /**
     * Checks entitlement on the server and, if allowed, issues a short-lived playback token. Only members-only
     * videos ask payments-service; public, unlisted, own and private videos are decided here, so ordinary
     * viewing keeps working even while payments is down.
     */
    public PlaybackAuthorisationDto authorisePlayback(Long videoId, Long userId) {
        VideoAccessDto access = requireAccess(videoId);
        PlaybackDecision decision = decide(userId, access);
        if (access.visibility() == VideoVisibility.PRIVATE && !decision.allowed()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        String sourceUrl = "/api/videos/" + videoId + "/file";
        String visibility = access.visibility().name();
        if (!decision.allowed()) {
            return new PlaybackAuthorisationDto(videoId, access.channelId(), visibility, access.requiredTierLevel(),
                    false, decision.reason(), null, null, null);
        }
        if (access.visibility().openToEveryone()) {
            return new PlaybackAuthorisationDto(videoId, access.channelId(), visibility, access.requiredTierLevel(),
                    true, null, sourceUrl, null, null);
        }
        Duration videoLength = access.duration() == null ? null : Duration.ofSeconds(access.duration().toSecondOfDay());
        PlaybackTokenService.IssuedToken token = playbackTokenService.issue(videoId, userId, videoLength);
        return new PlaybackAuthorisationDto(videoId, access.channelId(), visibility, access.requiredTierLevel(),
                true, null, sourceUrl + "?token=" + token.token(), token.token(), token.expiresAt());
    }

    /**
     * The ads to play during a video, if it is longer than a minute, the creator enabled ads and any public ad
     * exists: as many as the creator chose (at most one per 10 minutes), spread evenly, so n ads play at
     * 1/(n+1), 2/(n+1), ... n/(n+1) of the way in. Ads are chosen at random per view, different ones where
     * possible. Empty for ads themselves (they never show ads).
     */
    public List<AdBreakDto> adBreaks(Long videoId, Long userId) {
        VideoAccessDto access = requireAccess(videoId);
        if (access.visibility() == VideoVisibility.PRIVATE && !access.ownerUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        if (access.duration() == null || access.duration().toSecondOfDay() <= MIN_SECONDS_FOR_ADS) return List.of();
        int count = Math.min(adCount(videoId), maxAdsFor(access.duration()));
        if (count < 1) return List.of();
        List<AdCandidate> pool = caches.adPool.get(0, key -> shards.scatter(shard ->
                videoRepository.findAdCandidates(Limit.of(MAX_AD_POOL_PER_SHARD))));
        if (pool.isEmpty()) return List.of();
        List<AdCandidate> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        List<AdBreakDto> breaks = new ArrayList<>();
        for (int slot = 1; slot <= count; slot++) {
            AdCandidate ad = shuffled.get((slot - 1) % shuffled.size());
            breaks.add(new AdBreakDto((double) slot / (count + 1), ad.videoId(), ad.title(), ad.channelName(),
                    "/api/videos/" + ad.videoId() + "/file", ad.duration().toSecondOfDay()));
        }
        return breaks;
    }

    /** How many ads play in the video; 0 if its ads are off. */
    private int adCount(Long videoId) {
        return caches.videoAds.get(videoId, key -> shards.onShardOf(videoId,
                () -> videoRepository.findAdCount(videoId)).orElse(0));
    }

    /** The most ads a video of this length may have: one per 10 minutes, and at least one. */
    public static int maxAdsFor(LocalTime duration) {
        return duration == null ? 1 : Math.max(1, duration.toSecondOfDay() / SECONDS_PER_AD);
    }

    /**
     * The number of ads to store for an upload: 0 when ads are off (and always for ads), otherwise the requested
     * number (one if not given), which must be between 1 and {@link #maxAdsFor}.
     */
    public static int resolveAdCount(VideoType videoType, boolean adsEnabled, Integer requested, LocalTime duration) {
        if (videoType == VideoType.AD || !adsEnabled) return 0;
        int count = requested == null ? 1 : requested;
        int max = maxAdsFor(duration);
        if (count < 1 || count > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, max == 1
                    ? "This video can have 1 ad (one per 10 minutes of video)"
                    : "This video can have between 1 and " + max + " ads (one per 10 minutes of video)");
        }
        return count;
    }

    private PlaybackDecision decide(Long userId, VideoAccessDto access) {
        if (access.visibility().openToEveryone()) return PlaybackDecision.allow();
        if (userId != null && userId.equals(access.ownerUserId())) return PlaybackDecision.allow();
        if (access.visibility() == VideoVisibility.PRIVATE) return PlaybackDecision.deny("PRIVATE");
        if (userId == null) return PlaybackDecision.deny("SIGN_IN_REQUIRED");
        // Members-only: payments knows who is a member. If it's down this throws 503 (paid content fails closed).
        return payments.decide(userId, access);
    }

    /**
     * Guards the media endpoints. Videos that aren't open to everyone require a valid playback
     * token, so a members-only file can't be fetched by guessing its URL.
     * Returns true if the video is open to everyone (and so may be cached publicly).
     */
    public boolean assertCanStream(Long videoId, String token) {
        VideoAccessDto access = requireAccessUncached(videoId);
        if (access.visibility().openToEveryone()) return true;
        if (!playbackTokenService.isValid(videoId, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Playback authorisation required");
        }
        return false;
    }

    // ------------------------------------------------------------------ owner edits

    public void updateVisibility(Long videoId, Long userId, VideoVisibility visibility, Integer requiredTierLevel) {
        VideoAccessDto access = requireAccessUncached(videoId);
        if (!access.ownerUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel owner can change visibility");
        }
        validateTierLevel(visibility, requiredTierLevel);
        shards.runOnShardOf(videoId, () -> transactionTemplate.executeWithoutResult(status -> {
            VideoEntity video = videoRepository.findById(videoId).orElseThrow();
            video.setVisibility(visibility);
            video.setRequiredTierLevel(visibility == VideoVisibility.MEMBERS_ONLY ? requiredTierLevel : null);
        }));
        caches.videoChanged(videoId);
        syncPublicRead(videoId);
    }

    public List<VideoSummaryDto> getVideosOwnedBy(Long userId) {
        return shards.onExistingOwnerShard(userId, () -> videoRepository.findVideoSummariesByOwner(userId), List::of);
    }

    public VideoSummaryDto getOwnedVideo(Long videoId, Long userId) {
        requireOwner(videoId, userId);
        return shards.onShardOf(videoId, () -> videoRepository.findVideoSummaryForOwner(videoId, userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    /** Owner-only edit of the video's details. A null thumbnail keeps the current one. */
    public VideoSummaryDto updateVideoDetails(Long videoId, Long userId, String title, VideoVisibility visibility,
                                              Integer requiredTierLevel, MediaUpload thumbnail) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video title is required");
        }
        if (title.trim().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video title must be 255 characters or fewer");
        }
        VideoVisibility newVisibility = visibility == null ? VideoVisibility.PUBLIC : visibility;
        validateTierLevel(newVisibility, requiredTierLevel);
        requireOwner(videoId, userId);

        // Upload before the transaction; swap the key inside it; remove whichever file lost afterwards
        String newThumbnailKey = null;
        if (thumbnail != null && thumbnail.size() > 0) {
            String videoKey = media(videoId).map(VideoMediaDto::videoKey).orElse(null);
            String folder = videoKey == null ? MediaKeys.newVideoFolder() : MediaKeys.folderOf(videoKey);
            newThumbnailKey = MediaKeys.thumbnail(folder);
            upload(newThumbnailKey, thumbnail);
        }
        String uploadedKey = newThumbnailKey;
        String[] replacedKey = new String[1];
        try {
            VideoSummaryDto summary = shards.onShardOf(videoId, () -> transactionTemplate.execute(status -> {
                VideoEntity video = videoRepository.findById(videoId).orElseThrow();
                video.setTitle(title.trim());
                video.setVisibility(newVisibility);
                video.setRequiredTierLevel(newVisibility == VideoVisibility.MEMBERS_ONLY ? requiredTierLevel : null);
                if (uploadedKey != null) {
                    replacedKey[0] = video.getThumbnailKey();
                    video.setThumbnailKey(uploadedKey);
                    video.setThumbnailContentType(thumbnail.contentType());
                }
                videoRepository.flush();
                return videoRepository.findVideoSummaryForOwner(videoId, userId).orElseThrow();
            }));
            caches.videoChanged(videoId);
            syncPublicRead(videoId);
            if (replacedKey[0] != null) deleteQuietly(replacedKey[0]);
            return summary;
        } catch (RuntimeException exception) {
            if (uploadedKey != null) deleteQuietly(uploadedKey);
            throw exception;
        }
    }

    /** The row goes now; stored files and engagement data are cleaned up by video.deleted consumers. */
    public void deleteVideo(Long videoId, Long userId) {
        requireOwner(videoId, userId);
        shards.runOnShardOf(videoId, () -> transactionTemplate.executeWithoutResult(status -> {
            List<String> keys = new ArrayList<>(videoRepository.findRenditionKeys(videoId));
            videoRepository.findMedia(videoId).ifPresent(media -> {
                if (media.videoKey() != null) keys.add(media.videoKey());
                if (media.thumbnailKey() != null) keys.add(media.thumbnailKey());
            });
            videoRepository.deleteVideoById(videoId);
            eventPublisher.publishEvent(new VideoDeletedEvent(videoId, keys));
        }));
        caches.videoChanged(videoId);
    }

    private void requireOwner(Long videoId, Long userId) {
        VideoAccessDto access = requireAccessUncached(videoId);
        if (!access.ownerUserId().equals(userId)) {
            // Don't reveal that someone else's private video exists
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
    }

    // ------------------------------------------------------------------ reads (cached)

    /** Owner and visibility of a video, from cache when possible; empty if it doesn't exist. */
    public Optional<VideoAccessDto> findAccess(Long videoId) {
        if (shards.physicalOf(videoId).isEmpty()) return Optional.empty();
        VideoAccessDto cached = caches.videoAccess.getIfPresent(videoId);
        if (cached != null) return Optional.of(cached);
        Optional<VideoAccessDto> loaded = shards.onShardOf(videoId, () -> videoRepository.findAccess(videoId));
        loaded.ifPresent(access -> caches.videoAccess.put(videoId, access));
        return loaded;
    }

    private VideoAccessDto requireAccess(Long videoId) {
        return findAccess(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    /** Owner checks for edits always read the database, never a cache. */
    private VideoAccessDto requireAccessUncached(Long videoId) {
        if (shards.physicalOf(videoId).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        return shards.onShardOf(videoId, () -> videoRepository.findAccess(videoId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    private Optional<VideoMediaDto> media(Long videoId) {
        if (shards.physicalOf(videoId).isEmpty()) return Optional.empty();
        VideoMediaDto cached = caches.videoMedia.getIfPresent(videoId);
        if (cached != null) return Optional.of(cached);
        Optional<VideoMediaDto> loaded = shards.onShardOf(videoId, () -> videoRepository.findMedia(videoId));
        loaded.ifPresent(media -> caches.videoMedia.put(videoId, media));
        return loaded;
    }

    /**
     * The home feed: the newest public and members-only videos across all shards. Each shard returns its newest
     * {@code limit}; merging those and keeping the top {@code limit} gives the exact global answer.
     * Cached for a few seconds per pod: at peak, thousands of home page loads per second become one query per
     * shard every 10 s.
     */
    public List<VideoSummaryDto> getVideos(int limit) {
        int size = Math.clamp(limit, 1, MAX_FEED_SIZE);
        return caches.feed.get(size, key -> shards.scatter(shard -> videoRepository.findFeed(Limit.of(size))).stream()
                .sorted(VideoRow.NEWEST_FIRST)
                .limit(size)
                .map(VideoRow::summary)
                .toList());
    }

    /**
     * For other services: summaries with owners, by id. Cached per video; the misses are fetched with one query per
     * shard involved, in parallel.
     */
    public List<InternalVideoSummary> internalSummaries(Collection<Long> videoIds) {
        Map<Long, VideoRow> rows = caches.videoRows.getAll(videoIds.stream().distinct().toList(), missing -> {
            Map<Integer, List<Long>> byShard = shards.groupByShard(new ArrayList<>(missing));
            return shards.scatterTo(byShard.keySet(), shard -> videoRepository.findRows(byShard.get(shard))).stream()
                    .collect(Collectors.toMap(VideoRow::videoId, Function.identity(), (a, b) -> a));
        });
        return videoIds.stream().map(rows::get).filter(Objects::nonNull).map(VideoRow::internal).toList();
    }

    public List<String> getQualities(Long videoId) {
        requireAccess(videoId);
        return shards.onShardOf(videoId, () -> videoRepository.findRenditionQualities(videoId));
    }

    public String getVideoFileKey(Long videoId) {
        return media(videoId).map(VideoMediaDto::videoKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    public String getThumbnailKey(Long videoId) {
        return media(videoId).map(VideoMediaDto::thumbnailKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Thumbnail not found"));
    }

    public String getRenditionKey(Long videoId, String quality) {
        return shards.onShardOf(videoId, () -> videoRepository.findRendition(videoId, quality)).map(RenditionRef::storageKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video quality not found"));
    }

    /** For the transcode worker when an old message has no source key. */
    public Optional<String> findSourceKey(Long videoId) {
        return media(videoId).map(VideoMediaDto::videoKey);
    }

    // ------------------------------------------------------------------ transcoding results

    /** Applies the worker's result. Idempotent: renditions are upserts, and status is simply set again. */
    public void applyTranscodeResult(VideoTranscodedEvent event) {
        Long videoId = event.videoId();
        if (shards.physicalOf(videoId).isEmpty()) return;
        boolean exists = shards.onShardOf(videoId, () -> transactionTemplate.execute(status -> {
            Optional<VideoEntity> video = videoRepository.findById(videoId);
            if (video.isEmpty()) return false;
            if (event.renditions() != null) {
                for (VideoTranscodedEvent.Rendition rendition : event.renditions()) {
                    videoRepository.saveRendition(videoId, rendition.quality(), "video/mp4", rendition.storageKey(),
                            rendition.sizeBytes());
                }
            }
            video.get().setProcessingStatus(event.status());
            return true;
        }));
        if (!exists) {
            // Deleted while transcoding: its files are orphans now
            if (event.renditions() != null) event.renditions().forEach(r -> deleteQuietly(r.storageKey()));
            return;
        }
        caches.videoChanged(videoId);
        syncPublicRead(videoId);
    }

    // ------------------------------------------------------------------ media serving

    /**
     * Serves a stored file. Public media goes through the CDN when one is configured (one stable, cacheable URL per
     * file); otherwise the browser is redirected to a pre-signed URL so the bytes go straight from storage and never
     * through this service. Stores that can't pre-sign fall back to bounded range reads, one 4 MB chunk per request.
     */
    public ResponseEntity<byte[]> serveMedia(String key, String rangeHeader, boolean openToEveryone, boolean isVideo) {
        if (key == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        Optional<URI> signedCdn = cdn.signedUrl(key, isVideo);
        if (signedCdn.isPresent()) {
            // The redirect is an access credential; CloudFront caches the bytes, never this redirect.
            return ResponseEntity.status(HttpStatus.FOUND).location(signedCdn.get())
                    .cacheControl(CacheControl.noStore().cachePrivate()).build();
        }
        if (openToEveryone) {
            Optional<URI> viaCdn = cdn.publicUrl(key, isVideo);
            if (viaCdn.isPresent()) {
                // Stable URL: browsers and the CDN may cache the redirect itself for a while too
                return ResponseEntity.status(HttpStatus.FOUND).location(viaCdn.get())
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic()).build();
            }
        }
        if (storageProperties.redirectPlayback()) {
            var url = storage.presignedGet(key, storageProperties.presignTtl());
            if (url.isPresent()) return redirect(url.get(), openToEveryone);
        }
        var info = storage.stat(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));
        return streamRange(key, info, rangeHeader, openToEveryone);
    }

    /**
     * Thumbnails need no token (they're shown in listings, including for members-only videos), so they go through the
     * CDN for every video except private ones.
     */
    public ResponseEntity<byte[]> serveThumbnail(Long videoId) {
        VideoAccessDto access = requireAccessUncached(videoId);
        if (access.visibility() == VideoVisibility.PRIVATE) {
            requireOwner(videoId, ITubeBackend.security.CurrentUser.require());
        }
        String key = getThumbnailKey(videoId);
        boolean listed = access.visibility() != VideoVisibility.PRIVATE;
        return serveMedia(key, null, listed, false);
    }

    /**
     * Tags the video's files public (or not) to match its visibility, so the CDN may serve exactly the media anyone
     * may see. Only when a CDN is configured; failures are logged and fixed by the next change.
     */
    private void syncPublicRead(Long videoId) {
        if (!cdn.requiresPublicTags()) return;
        try {
            VideoVisibility visibility = findAccess(videoId).map(VideoAccessDto::visibility).orElse(VideoVisibility.PRIVATE);
            boolean open = visibility.openToEveryone();
            List<String> videoKeys = new ArrayList<>(shards.onShardOf(videoId, () -> videoRepository.findRenditionKeys(videoId)));
            Optional<VideoMediaDto> media = media(videoId);
            media.map(VideoMediaDto::videoKey).ifPresent(videoKeys::add);
            for (String key : videoKeys) storage.setPublicRead(key, open);
            media.map(VideoMediaDto::thumbnailKey)
                    .ifPresent(key -> storage.setPublicRead(key, visibility != VideoVisibility.PRIVATE));
        } catch (RuntimeException exception) {
            log.warn("Could not update public-read tags for video {}: {}", videoId, exception.toString());
        }
    }

    private static ResponseEntity<byte[]> redirect(URI url, boolean openToEveryone) {
        // The signed URL itself expires, so only let the redirect be cached briefly (and never for paid media)
        CacheControl cache = openToEveryone ? CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()
                : CacheControl.noStore().cachePrivate();
        return ResponseEntity.status(HttpStatus.FOUND).location(url).cacheControl(cache).build();
    }

    private ResponseEntity<byte[]> streamRange(String key, ObjectStorage.ObjectInfo info, String rangeHeader,
                                               boolean openToEveryone) {
        long total = info.length();
        MediaType contentType = info.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(info.contentType());
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return mediaResponse(HttpStatus.OK, openToEveryone).contentType(contentType).header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(storage.readRange(key, 0, Math.toIntExact(total)));
        }

        long start;
        long end;
        try {
            List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
            if (ranges.isEmpty()) throw new IllegalArgumentException("Empty range");
            start = ranges.getFirst().getRangeStart(total);
            if (start >= total) throw new IllegalArgumentException("Range starts past end of file");
            end = Math.min(ranges.getFirst().getRangeEnd(total), start + MAX_CHUNK_BYTES - 1);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total).build();
        }

        byte[] chunk = storage.readRange(key, start, Math.toIntExact(end - start + 1));
        return mediaResponse(HttpStatus.PARTIAL_CONTENT, openToEveryone).contentType(contentType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total)
                .body(chunk);
    }

    // Paid or private media must never be stored by shared caches
    private static ResponseEntity.BodyBuilder mediaResponse(HttpStatus status, boolean openToEveryone) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        return openToEveryone ? builder : builder.cacheControl(CacheControl.noStore().cachePrivate());
    }

    private static void validateTierLevel(VideoVisibility visibility, Integer requiredTierLevel) {
        if (requiredTierLevel == null) return;
        if (visibility != VideoVisibility.MEMBERS_ONLY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A tier level only applies to members-only videos");
        }
        if (requiredTierLevel < 1 || requiredTierLevel > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tier level must be between 1 and 10");
        }
    }

    private void upload(String key, MediaUpload upload) {
        String contentType = upload.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : upload.contentType();
        try (InputStream in = upload.source().getInputStream()) {
            storage.put(key, in, upload.size(), contentType);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read the uploaded file", exception);
        }
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Could not remove orphaned object {}: {}", key, exception.toString());
        }
    }
}
