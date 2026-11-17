package ITubeBackend.service.video;

import ITubeBackend.dto.video.CreateVideo;
import ITubeBackend.dto.video.MediaUpload;
import ITubeBackend.dto.video.PlaybackAuthorisationDto;
import ITubeBackend.dto.video.RenditionRef;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoDeletedEvent;
import ITubeBackend.dto.video.VideoMediaDto;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.dto.video.VideoUploadedEvent;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.entities.VideoEntity;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.service.entitlement.EntitlementService;
import ITubeBackend.service.entitlement.PlaybackTokenService;
import ITubeBackend.storage.MediaKeys;
import ITubeBackend.storage.ObjectStorage;
import ITubeBackend.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;


@Service
public class VideoService {
    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    private static final long MAX_CHUNK_BYTES = 4L * 1024 * 1024;
    private final ChannelRepository channelRepository;
    private final VideoRepository videoRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EntitlementService entitlementService;
    private final PlaybackTokenService playbackTokenService;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    private final TransactionTemplate transactionTemplate;

    public VideoService(ChannelRepository channelRepository, VideoRepository videoRepository,
                        ApplicationEventPublisher eventPublisher, EntitlementService entitlementService,
                        PlaybackTokenService playbackTokenService, ObjectStorage storage,
                        StorageProperties storageProperties, TransactionTemplate transactionTemplate) {
        this.channelRepository = channelRepository;
        this.videoRepository = videoRepository;
        this.eventPublisher = eventPublisher;
        this.entitlementService = entitlementService;
        this.playbackTokenService = playbackTokenService;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Streams the files to object storage first, then records the video in a short transaction, so a
     * multi-minute upload never holds a database connection. If the insert fails the files are removed.
     */
    public Long saveVideo(CreateVideo createVideo) {
        if (createVideo.title() == null || createVideo.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video title is required");
        }
        if (createVideo.video() == null || createVideo.video().size() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A video file is required");
        }
        if (createVideo.duration() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video duration is required");
        }

        VideoVisibility visibility = createVideo.visibility() == null ? VideoVisibility.PUBLIC : createVideo.visibility();
        validateTierLevel(visibility, createVideo.requiredTierLevel());
        if (!channelRepository.existsById(createVideo.channelId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }

        String folder = MediaKeys.newVideoFolder();
        List<String> uploaded = new ArrayList<>();
        try {
            String videoKey = MediaKeys.source(folder);
            upload(videoKey, createVideo.video());
            uploaded.add(videoKey);
            String thumbnailKey = null;
            if (createVideo.thumbnail() != null && createVideo.thumbnail().size() > 0) {
                thumbnailKey = MediaKeys.thumbnail(folder);
                upload(thumbnailKey, createVideo.thumbnail());
                uploaded.add(thumbnailKey);
            }
            String finalThumbnailKey = thumbnailKey;
            return transactionTemplate.execute(status -> {
                ChannelEntity channel = channelRepository.findById(createVideo.channelId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
                VideoEntity video = createVideoEntity(createVideo, visibility, channel, videoKey, finalThumbnailKey);
                Long videoId = videoRepository.save(video).getVideoId();
                eventPublisher.publishEvent(new VideoUploadedEvent(videoId));
                return videoId;
            });
        } catch (RuntimeException exception) {
            uploaded.forEach(this::deleteQuietly);
            throw exception;
        }
    }

    private static VideoEntity createVideoEntity(CreateVideo createVideo, VideoVisibility visibility, ChannelEntity channel,
                                                 String videoKey, String thumbnailKey) {
        VideoEntity video = new VideoEntity();
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
        video.setProcessingStatus("PROCESSING");
        return video;
    }

    /** Checks entitlement on the server and, if allowed, issues a short-lived playback token. */
    @Transactional(readOnly = true)
    public PlaybackAuthorisationDto authorisePlayback(Long videoId, Long userId) {
        VideoAccessDto access = requireAccess(videoId);
        EntitlementService.PlaybackDecision decision = entitlementService.decide(userId, access);
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
     * Guards the media endpoints. Videos that aren't open to everyone require a valid playback
     * token, so a members-only file can't be fetched by guessing its URL.
     * Returns true if the video is open to everyone (and so may be cached publicly).
     */
    @Transactional(readOnly = true)
    public boolean assertCanStream(Long videoId, String token) {
        VideoAccessDto access = requireAccess(videoId);
        if (access.visibility().openToEveryone()) return true;
        if (!playbackTokenService.isValid(videoId, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Playback authorisation required");
        }
        return false;
    }

    @Transactional
    public void updateVisibility(Long videoId, Long userId, VideoVisibility visibility, Integer requiredTierLevel) {
        VideoAccessDto access = requireAccess(videoId);
        if (!access.ownerUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel owner can change visibility");
        }
        validateTierLevel(visibility, requiredTierLevel);
        VideoEntity video = videoRepository.findById(videoId).orElseThrow();
        video.setVisibility(visibility);
        video.setRequiredTierLevel(visibility == VideoVisibility.MEMBERS_ONLY ? requiredTierLevel : null);
    }

    @Transactional(readOnly = true)
    public List<VideoSummaryDto> getVideosOwnedBy(Long userId) {
        return videoRepository.findVideoSummariesByOwner(userId);
    }

    @Transactional(readOnly = true)
    public VideoSummaryDto getOwnedVideo(Long videoId, Long userId) {
        requireOwner(videoId, userId);
        return videoRepository.findVideoSummaryForOwner(videoId, userId)
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
            String videoKey = videoRepository.findMedia(videoId).map(VideoMediaDto::videoKey).orElse(null);
            String folder = videoKey == null ? MediaKeys.newVideoFolder() : MediaKeys.folderOf(videoKey);
            newThumbnailKey = MediaKeys.thumbnail(folder);
            upload(newThumbnailKey, thumbnail);
        }
        String uploadedKey = newThumbnailKey;
        String[] replacedKey = new String[1];
        try {
            VideoSummaryDto summary = transactionTemplate.execute(status -> {
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
            });
            if (replacedKey[0] != null) deleteQuietly(replacedKey[0]);
            return summary;
        } catch (RuntimeException exception) {
            if (uploadedKey != null) deleteQuietly(uploadedKey);
            throw exception;
        }
    }

    /** The row goes now; stored files and engagement data are cleaned up by video.deleted consumers. */
    @Transactional
    public void deleteVideo(Long videoId, Long userId) {
        requireOwner(videoId, userId);
        List<String> keys = new ArrayList<>(videoRepository.findRenditionKeys(videoId));
        videoRepository.findMedia(videoId).ifPresent(media -> {
            if (media.videoKey() != null) keys.add(media.videoKey());
            if (media.thumbnailKey() != null) keys.add(media.thumbnailKey());
        });
        videoRepository.deleteVideoById(videoId);
        eventPublisher.publishEvent(new VideoDeletedEvent(videoId, keys));
    }

    private void requireOwner(Long videoId, Long userId) {
        VideoAccessDto access = requireAccess(videoId);
        if (!access.ownerUserId().equals(userId)) {
            // Don't reveal that someone else's private video exists
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
    }

    private VideoAccessDto requireAccess(Long videoId) {
        return videoRepository.findAccess(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
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

    @Transactional(readOnly = true)
    public List<VideoSummaryDto> getVideos() {
        return videoRepository.findVideoSummaries();
    }

    @Transactional(readOnly = true)
    public List<String> getQualities(Long videoId) {
        if (!videoRepository.existsById(videoId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        return videoRepository.findRenditionQualities(videoId);
    }

    @Transactional(readOnly = true)
    public String getVideoFileKey(Long videoId) {
        return videoRepository.findMedia(videoId).map(VideoMediaDto::videoKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    @Transactional(readOnly = true)
    public String getThumbnailKey(Long videoId) {
        return videoRepository.findMedia(videoId).map(VideoMediaDto::thumbnailKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Thumbnail not found"));
    }

    @Transactional(readOnly = true)
    public String getRenditionKey(Long videoId, String quality) {
        return videoRepository.findRendition(videoId, quality).map(RenditionRef::storageKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video quality not found"));
    }

    @Transactional(readOnly = true)
    public VideoMediaDto getSourceForTranscoding(Long videoId) {
        return videoRepository.findMedia(videoId)
                .filter(media -> media.videoKey() != null)
                .orElseThrow(() -> new IllegalStateException("Video " + videoId + " has no stored source file"));
    }

    @Transactional
    public void saveRendition(Long videoId, String quality, String storageKey, long sizeBytes) {
        videoRepository.saveRendition(videoId, quality, "video/mp4", storageKey, sizeBytes);
    }

    @Transactional
    public void setProcessingStatus(Long videoId, String status) {
        videoRepository.findById(videoId).ifPresent(video -> video.setProcessingStatus(status));
    }

    /**
     * Serves a stored file. Normally the browser is redirected to a pre-signed URL so the bytes go
     * straight from storage (or a CDN in front of it) and never through this backend. Stores that
     * can't pre-sign fall back to bounded range reads, one 4 MB chunk per request.
     */
    public ResponseEntity<byte[]> serveMedia(String key, String rangeHeader, boolean openToEveryone) {
        if (key == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        if (storageProperties.redirectPlayback()) {
            var url = storage.presignedGet(key, storageProperties.presignTtl());
            if (url.isPresent()) return redirect(url.get(), openToEveryone);
        }
        var info = storage.stat(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));
        return streamRange(key, info, rangeHeader, openToEveryone);
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
