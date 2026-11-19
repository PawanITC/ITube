package ITubeBackend.service.video;

import ITubeBackend.config.UploadProperties;
import ITubeBackend.dto.video.CreateVideo;
import ITubeBackend.dto.video.MediaUpload;
import ITubeBackend.dto.video.ResumableUploadDtos.PartUrlDto;
import ITubeBackend.dto.video.ResumableUploadDtos.StartUploadRequest;
import ITubeBackend.dto.video.ResumableUploadDtos.UploadSessionDto;
import ITubeBackend.entities.UploadSessionEntity;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.UploadSessionRepository;
import ITubeBackend.sharding.Shards;
import ITubeBackend.storage.MediaKeys;
import ITubeBackend.storage.MultipartUploadNotFoundException;
import ITubeBackend.storage.ObjectStorage;
import ITubeBackend.storage.ObjectStorage.UploadedPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resumable video uploads. The browser sends the file as numbered parts, normally straight to object storage
 * through pre-signed URLs (an S3 multipart upload), so the bytes never pass through the backend and a
 * half-finished upload survives closing the tab. Object storage is the source of truth for which parts
 * arrived; the upload_session row only remembers the upload and the video details. Completing the upload
 * joins the parts and records the video exactly like {@link VideoService#saveVideo}.
 * <p>
 * Sharding: upload sessions live on the uploader's shard (with their channels and videos), so each public method
 * below first routes to that shard; the expiry job visits every shard.
 */
@Service
public class ResumableUploadService {
    private static final Logger log = LoggerFactory.getLogger(ResumableUploadService.class);
    /** S3 needs parts of at least 5 MiB (except the last); 8 MiB keeps the part count low. */
    static final long MIN_PART_SIZE = 8L * 1024 * 1024;
    /** S3 allows at most 10,000 parts per upload. */
    static final int MAX_PARTS = 10_000;
    private static final long MIB = 1024L * 1024;
    private static final int MAX_PART_URLS_PER_REQUEST = 100;

    private final UploadSessionRepository sessions;
    private final ObjectStorage storage;
    private final VideoService videoService;
    private final UploadProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Shards shards;

    public ResumableUploadService(UploadSessionRepository sessions, ObjectStorage storage, VideoService videoService,
                                  UploadProperties properties, TransactionTemplate transactionTemplate, Clock clock,
                                  Shards shards) {
        this.shards = shards;
        this.sessions = sessions;
        this.storage = storage;
        this.videoService = videoService;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public UploadSessionDto start(Long userId, Long channelId, StartUploadRequest request) {
        // The channel comes from the session (one of the user's own), so it is on the user's shard
        if (channelId == null || shards.physicalOf(channelId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }
        return shards.onOwnerShard(userId, () -> startOnShard(userId, channelId, request));
    }

    private UploadSessionDto startOnShard(Long userId, Long channelId, StartUploadRequest request) {
        if (!shards.physicalOf(channelId).equals(java.util.OptionalInt.of(ITubeBackend.sharding.ShardContext.current()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only upload to your own channel");
        }
        String fileName = request.fileName().strip();
        if (fileName.length() > 255) fileName = fileName.substring(fileName.length() - 255);
        if (request.fileSize() > properties.maxFileSize().toBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Videos can be at most " + properties.maxFileSize().toGigabytes() + " GB");
        }
        String contentType = request.contentType() == null || request.contentType().isBlank()
                ? "application/octet-stream" : request.contentType().strip();
        if (!contentType.startsWith("video/") && !contentType.equals("application/octet-stream")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Choose a video file");
        }
        if (contentType.length() > 100) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Choose a video file");
        }
        VideoVisibility visibility = videoService.validateDetails(channelId, request.title(), request.duration(),
                request.visibility(), request.requiredTierLevel());
        Integer tier = visibility == VideoVisibility.MEMBERS_ONLY ? request.requiredTierLevel() : null;
        String title = request.title().strip();
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Same file picked again: resume the open upload, with the details as entered now
        Optional<UploadSessionEntity> existing = sessions
                .findByUserIdAndFileNameAndFileSizeAndFileLastModifiedAndExpiresAtAfter(
                        userId, fileName, request.fileSize(), request.lastModified(), now)
                .stream()
                .filter(session -> session.getChannelId().equals(channelId))
                .filter(session -> UploadSessionEntity.UPLOADING.equals(session.getStatus()))
                .findFirst();
        if (existing.isPresent()) {
            UploadSessionEntity session = existing.get();
            transactionTemplate.executeWithoutResult(status -> {
                UploadSessionEntity managed = sessions.findById(session.getUploadSessionId()).orElseThrow();
                managed.setDetails(title, request.duration(), visibility, tier);
            });
            return toDto(sessions.findById(session.getUploadSessionId()).orElseThrow());
        }

        if (sessions.countByUserIdAndExpiresAtAfter(userId, now) >= properties.maxOpenUploads()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You have too many unfinished uploads. Finish or discard one first.");
        }

        long partSize = partSizeFor(request.fileSize());
        String key = MediaKeys.source(MediaKeys.newVideoFolder());
        String storageUploadId = storage.createMultipartUpload(key, contentType);
        UploadSessionEntity session = new UploadSessionEntity(UUID.randomUUID(), userId, channelId, key, storageUploadId,
                fileName, request.fileSize(), request.lastModified(), contentType, partSize,
                now, now.plus(properties.uploadTtl()));
        session.setDetails(title, request.duration(), visibility, tier);
        try {
            transactionTemplate.executeWithoutResult(status -> sessions.save(session));
        } catch (RuntimeException exception) {
            abortQuietly(key, storageUploadId);
            throw exception;
        }
        return toDto(session, List.of());
    }

    /** The user's unfinished uploads, newest first, with how far each got. */
    public List<UploadSessionDto> listOpen(Long userId) {
        return shards.onExistingOwnerShard(userId, () -> listOpenOnShard(userId), List::of);
    }

    private List<UploadSessionDto> listOpenOnShard(Long userId) {
        List<UploadSessionDto> open = new ArrayList<>();
        for (UploadSessionEntity session : sessions.findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(userId, OffsetDateTime.now(clock))) {
            try {
                open.add(toDto(session));
            } catch (ResponseStatusException gone) {
                // Object storage dropped it (e.g. the bucket's lifecycle rule); toDto removed the row
            }
        }
        return open;
    }

    public UploadSessionDto get(UUID uploadId, Long userId) {
        return onUploaderShard(userId, () -> toDto(requireOwned(uploadId, userId)));
    }

    /** Sessions are on the uploader's shard; a user with no shard has no uploads. */
    private <T> T onUploaderShard(Long userId, java.util.function.Supplier<T> work) {
        return shards.onExistingOwnerShard(userId, work, () -> {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload not found or expired");
        });
    }

    public List<PartUrlDto> partUrls(UUID uploadId, Long userId, List<Integer> partNumbers) {
        return onUploaderShard(userId, () -> partUrlsOnShard(uploadId, userId, partNumbers));
    }

    private List<PartUrlDto> partUrlsOnShard(UUID uploadId, Long userId, List<Integer> partNumbers) {
        UploadSessionEntity session = requireUploading(uploadId, userId);
        if (partNumbers.size() > MAX_PART_URLS_PER_REQUEST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask for at most " + MAX_PART_URLS_PER_REQUEST + " parts at once");
        }
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(properties.partUrlTtl());
        return partNumbers.stream().distinct().map(partNumber -> {
            requireValidPart(session, partNumber);
            Optional<URI> direct = storage.presignedUploadPart(session.getStorageKey(), session.getStorageUploadId(),
                    partNumber, properties.partUrlTtl());
            return direct
                    .map(url -> new PartUrlDto(partNumber, url.toString(), false, expiresAt))
                    .orElseGet(() -> new PartUrlDto(partNumber,
                            "/api/uploads/" + uploadId + "/parts/" + partNumber, true, null));
        }).toList();
    }

    /** For stores that can't pre-sign (local development): the part's bytes come through the backend. */
    public String uploadPart(UUID uploadId, Long userId, int partNumber, InputStream content, long contentLength) {
        // Only the session lookup needs the database; the bytes stream to object storage without holding a connection
        UploadSessionEntity session = onUploaderShard(userId, () -> requireUploading(uploadId, userId));
        requireValidPart(session, partNumber);
        long expected = session.partLength(partNumber);
        if (contentLength != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Part " + partNumber + " must be " + expected + " bytes, got " + contentLength);
        }
        try {
            return storage.uploadPart(session.getStorageKey(), session.getStorageUploadId(), partNumber, content, expected);
        } catch (MultipartUploadNotFoundException missing) {
            throw onUploaderShard(userId, () -> forgetLostUpload(session));
        }
    }

    /**
     * Joins the parts into the video's source object and records the video (which starts transcoding).
     * Safe to retry: once the parts are joined the session is marked ASSEMBLED, and the session row is
     * deleted in the same transaction that inserts the video, so a second call can't create a duplicate.
     */
    public Long complete(UUID uploadId, Long userId, MediaUpload thumbnail) {
        return onUploaderShard(userId, () -> completeOnShard(uploadId, userId, thumbnail));
    }

    private Long completeOnShard(UUID uploadId, Long userId, MediaUpload thumbnail) {
        UploadSessionEntity session = requireOwned(uploadId, userId);
        if (UploadSessionEntity.UPLOADING.equals(session.getStatus())) {
            assemble(session);
            transactionTemplate.executeWithoutResult(status -> sessions.findById(uploadId)
                    .ifPresent(managed -> managed.setStatus(UploadSessionEntity.ASSEMBLED)));
        }
        ObjectStorage.ObjectInfo info = storage.stat(session.getStorageKey())
                .orElseThrow(() -> new IllegalStateException("Assembled upload " + uploadId + " has no source object"));
        if (info.length() != session.getFileSize()) {
            throw new IllegalStateException("Assembled upload " + uploadId + " is " + info.length()
                    + " bytes, expected " + session.getFileSize());
        }

        CreateVideo video = new CreateVideo(session.getChannelId(),
                new MediaUpload(null, session.getFileSize(), session.getContentType()), thumbnail,
                session.getTitle(), session.getDuration(), session.getVisibility(), session.getRequiredTierLevel());
        return videoService.saveUploadedVideo(video, session.getStorageKey(), () -> {
            if (sessions.deleteSession(uploadId) == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This upload was already posted");
            }
        });
    }

    /** Abandons the upload: its parts are deleted and it can't be resumed. */
    public void discard(UUID uploadId, Long userId) {
        onUploaderShard(userId, () -> {
            remove(requireOwned(uploadId, userId));
            return null;
        });
    }

    /** Aborts uploads whose time ran out, on every shard. Returns how many were removed. */
    public int removeExpired(int batchSize) {
        return shards.scatter(shard -> List.of(removeExpiredOnShard(batchSize))).stream().mapToInt(Integer::intValue).sum();
    }

    private int removeExpiredOnShard(int batchSize) {
        int removed = 0;
        for (UploadSessionEntity session : sessions.findByExpiresAtBefore(OffsetDateTime.now(clock), Limit.of(batchSize))) {
            try {
                remove(session);
                removed++;
            } catch (RuntimeException exception) {
                log.warn("Could not remove expired upload {}: {}", session.getUploadSessionId(), exception.toString());
            }
        }
        return removed;
    }

    private void assemble(UploadSessionEntity session) {
        List<UploadedPart> stored;
        try {
            stored = storage.listParts(session.getStorageKey(), session.getStorageUploadId());
        } catch (MultipartUploadNotFoundException missing) {
            if (alreadyAssembled(session)) return; // a concurrent complete got there first
            throw forgetLostUpload(session);
        }
        Map<Integer, UploadedPart> byNumber = stored.stream()
                .collect(Collectors.toMap(UploadedPart::partNumber, Function.identity(), (first, second) -> second));
        List<UploadedPart> parts = new ArrayList<>();
        int missing = 0;
        for (int partNumber = 1; partNumber <= session.partCount(); partNumber++) {
            UploadedPart part = byNumber.get(partNumber);
            if (part == null || part.size() != session.partLength(partNumber)) missing++;
            else parts.add(part);
        }
        if (missing > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    missing + " of " + session.partCount() + " parts haven't been uploaded yet");
        }
        try {
            storage.completeMultipartUpload(session.getStorageKey(), session.getStorageUploadId(), parts);
        } catch (MultipartUploadNotFoundException missingUpload) {
            if (!alreadyAssembled(session)) throw forgetLostUpload(session);
        }
    }

    private boolean alreadyAssembled(UploadSessionEntity session) {
        return storage.stat(session.getStorageKey()).filter(info -> info.length() == session.getFileSize()).isPresent();
    }

    /** Object storage no longer has the upload, so it can't be resumed: drop the row and tell the user. */
    private ResponseStatusException forgetLostUpload(UploadSessionEntity session) {
        transactionTemplate.executeWithoutResult(status -> sessions.deleteSession(session.getUploadSessionId()));
        return new ResponseStatusException(HttpStatus.GONE, "This upload expired. Please upload the video again.");
    }

    private void remove(UploadSessionEntity session) {
        storage.abortMultipartUpload(session.getStorageKey(), session.getStorageUploadId());
        if (UploadSessionEntity.ASSEMBLED.equals(session.getStatus())) storage.delete(session.getStorageKey());
        transactionTemplate.executeWithoutResult(status -> sessions.deleteSession(session.getUploadSessionId()));
    }

    private UploadSessionEntity requireOwned(UUID uploadId, Long userId) {
        return sessions.findById(uploadId)
                .filter(session -> session.getUserId().equals(userId))
                .filter(session -> session.getExpiresAt().isAfter(OffsetDateTime.now(clock)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload not found or expired"));
    }

    private UploadSessionEntity requireUploading(UUID uploadId, Long userId) {
        UploadSessionEntity session = requireOwned(uploadId, userId);
        if (!UploadSessionEntity.UPLOADING.equals(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Every part of this upload has already arrived");
        }
        return session;
    }

    private static void requireValidPart(UploadSessionEntity session, Integer partNumber) {
        if (partNumber == null || partNumber < 1 || partNumber > session.partCount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Part numbers run from 1 to " + session.partCount());
        }
    }

    /** At least MIN_PART_SIZE, large enough that the file fits in MAX_PARTS parts, in whole MiB. */
    static long partSizeFor(long fileSize) {
        long needed = Math.max(MIN_PART_SIZE, (fileSize + MAX_PARTS - 1) / MAX_PARTS);
        return (needed + MIB - 1) / MIB * MIB;
    }

    private UploadSessionDto toDto(UploadSessionEntity session) {
        if (!UploadSessionEntity.UPLOADING.equals(session.getStatus())) {
            List<Integer> all = new ArrayList<>();
            for (int partNumber = 1; partNumber <= session.partCount(); partNumber++) all.add(partNumber);
            return toDto(session, all);
        }
        try {
            List<Integer> uploaded = storage.listParts(session.getStorageKey(), session.getStorageUploadId()).stream()
                    .filter(part -> part.partNumber() >= 1 && part.partNumber() <= session.partCount())
                    .filter(part -> part.size() == session.partLength(part.partNumber()))
                    .map(UploadedPart::partNumber)
                    .toList();
            return toDto(session, uploaded);
        } catch (MultipartUploadNotFoundException missing) {
            throw forgetLostUpload(session);
        }
    }

    private static UploadSessionDto toDto(UploadSessionEntity session, List<Integer> uploadedParts) {
        long uploadedBytes = uploadedParts.stream().mapToLong(session::partLength).sum();
        return new UploadSessionDto(session.getUploadSessionId(), session.getFileName(), session.getFileSize(),
                session.getFileLastModified(), session.getContentType(), session.getPartSize(), session.partCount(),
                uploadedParts, uploadedBytes, session.getTitle(), session.getDuration(), session.getVisibility(),
                session.getRequiredTierLevel(), session.getCreatedAt(), session.getExpiresAt());
    }

    private void abortQuietly(String key, String storageUploadId) {
        try {
            storage.abortMultipartUpload(key, storageUploadId);
        } catch (RuntimeException exception) {
            log.warn("Could not abort upload {} of {}: {}", storageUploadId, key, exception.toString());
        }
    }
}
