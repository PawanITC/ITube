package ITubeBackend;

import ITubeBackend.config.UploadProperties;
import ITubeBackend.dto.video.CreateVideo;
import ITubeBackend.dto.video.ResumableUploadDtos.PartUrlDto;
import ITubeBackend.dto.video.ResumableUploadDtos.StartUploadRequest;
import ITubeBackend.dto.video.ResumableUploadDtos.UploadSessionDto;
import ITubeBackend.entities.UploadSessionEntity;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.UploadSessionRepository;
import ITubeBackend.service.video.ResumableUploadService;
import ITubeBackend.service.video.VideoService;
import ITubeBackend.storage.LocalObjectStorage;
import ITubeBackend.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Resumable uploads: parts survive between visits, and only a complete set of parts becomes a video. */
class ResumableUploadTest {
    private static final long USER = 7L;
    private static final long CHANNEL = 5L;
    private static final int PART = 8 * 1024 * 1024;

    @TempDir
    Path storageDir;

    private final Map<UUID, UploadSessionEntity> rows = new LinkedHashMap<>();
    private final UploadSessionRepository sessions = mock(UploadSessionRepository.class);
    private final VideoService videos = mock(VideoService.class);
    private Clock clock = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);
    private ObjectStorage storage;
    private ResumableUploadService uploads;

    @BeforeEach
    void setUp() {
        storage = new LocalObjectStorage(storageDir);
        when(sessions.save(any())).thenAnswer(call -> {
            UploadSessionEntity row = call.getArgument(0);
            rows.put(row.getUploadSessionId(), row);
            return row;
        });
        when(sessions.findById(any())).thenAnswer(call -> Optional.ofNullable(rows.get(call.<UUID>getArgument(0))));
        when(sessions.deleteSession(any())).thenAnswer(call -> rows.remove(call.<UUID>getArgument(0)) == null ? 0 : 1);
        when(sessions.countByUserIdAndExpiresAtAfter(any(), any())).thenAnswer(call -> (long) rows.size());
        when(sessions.findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(any(), any()))
                .thenAnswer(call -> live(call.getArgument(1)).toList());
        when(sessions.findByUserIdAndFileNameAndFileSizeAndFileLastModifiedAndExpiresAtAfter(any(), anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(call -> live(call.getArgument(4))
                        .filter(row -> row.getFileName().equals(call.getArgument(1)))
                        .filter(row -> row.getFileSize() == call.<Long>getArgument(2))
                        .filter(row -> row.getFileLastModified() == call.<Long>getArgument(3))
                        .toList());
        when(sessions.findByExpiresAtBefore(any(), any())).thenAnswer(call -> rows.values().stream()
                .filter(row -> row.getExpiresAt().isBefore(call.getArgument(0))).toList());
        when(videos.validateDetails(any(), any(), any(), any(), any())).thenReturn(VideoVisibility.PUBLIC);
        when(videos.saveUploadedVideo(any(), any(), any())).thenAnswer(call -> {
            call.<Runnable>getArgument(2).run();
            return 42L;
        });
        uploads = service();
    }

    private ResumableUploadService service() {
        UploadProperties properties = new UploadProperties(DataSize.ofMegabytes(100), Duration.ofDays(7), Duration.ofHours(1), 3);
        return new ResumableUploadService(sessions, storage, videos, properties,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), clock);
    }

    private Stream<UploadSessionEntity> live(OffsetDateTime now) {
        return rows.values().stream().filter(row -> row.getExpiresAt().isAfter(now));
    }

    private static StartUploadRequest request(byte[] file) {
        return new StartUploadRequest("clip.mp4", file.length, 1_700_000_000_000L, "video/mp4", "My clip",
                LocalTime.of(0, 2), VideoVisibility.PUBLIC, null);
    }

    private static byte[] file(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31);
        return bytes;
    }

    private void sendPart(UUID uploadId, byte[] file, int partNumber) {
        int start = (partNumber - 1) * PART;
        byte[] part = Arrays.copyOfRange(file, start, Math.min(start + PART, file.length));
        uploads.uploadPart(uploadId, USER, partNumber, new ByteArrayInputStream(part), part.length);
    }

    @Test
    void anInterruptedUploadResumesWithOnlyTheMissingPartsAndThenBecomesAVideo() throws Exception {
        byte[] file = file(2 * PART + 5);
        UploadSessionDto started = uploads.start(USER, CHANNEL, request(file));
        assertThat(started.partSize()).isEqualTo(PART);
        assertThat(started.partCount()).isEqualTo(3);
        assertThat(started.uploadedParts()).isEmpty();

        List<PartUrlDto> targets = uploads.partUrls(started.uploadId(), USER, List.of(1, 2, 3));
        assertThat(targets).allSatisfy(target -> assertThat(target.viaBackend()).isTrue());
        assertThat(targets.get(0).url()).isEqualTo("/api/uploads/" + started.uploadId() + "/parts/1");

        sendPart(started.uploadId(), file, 1);
        sendPart(started.uploadId(), file, 3);

        // Later visit: choosing the same file again resumes the same upload
        UploadSessionDto resumed = uploads.start(USER, CHANNEL, request(file));
        assertThat(resumed.uploadId()).isEqualTo(started.uploadId());
        assertThat(resumed.uploadedParts()).containsExactly(1, 3);
        assertThat(resumed.uploadedBytes()).isEqualTo(PART + 5);
        assertThat(uploads.listOpen(USER)).extracting(UploadSessionDto::uploadId).containsExactly(started.uploadId());

        assertThatThrownBy(() -> uploads.complete(started.uploadId(), USER, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(videos, never()).saveUploadedVideo(any(), any(), any());

        sendPart(started.uploadId(), file, 2);
        assertThat(uploads.complete(started.uploadId(), USER, null)).isEqualTo(42L);

        ArgumentCaptor<CreateVideo> video = ArgumentCaptor.forClass(CreateVideo.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(videos).saveUploadedVideo(video.capture(), key.capture(), any());
        assertThat(video.getValue().video().size()).isEqualTo(file.length);
        assertThat(video.getValue().title()).isEqualTo("My clip");
        assertThat(key.getValue()).matches("videos/[0-9a-f-]{36}/source");
        assertThat(storage.readRange(key.getValue(), 0, file.length)).isEqualTo(file);
        assertThat(rows).isEmpty();
        assertThat(uploads.listOpen(USER)).isEmpty();
    }

    @Test
    void retryingCompleteAfterTheVideoInsertFailedDoesNotNeedThePartsAgain() {
        byte[] file = file(PART + 1);
        UploadSessionDto started = uploads.start(USER, CHANNEL, request(file));
        sendPart(started.uploadId(), file, 1);
        sendPart(started.uploadId(), file, 2);
        doThrow(new IllegalStateException("database down")).when(videos).saveUploadedVideo(any(), any(), any());
        assertThatThrownBy(() -> uploads.complete(started.uploadId(), USER, null)).hasMessageContaining("database down");
        assertThat(rows.get(started.uploadId()).getStatus()).isEqualTo(UploadSessionEntity.ASSEMBLED);

        doAnswer(call -> {
            call.<Runnable>getArgument(2).run();
            return 43L;
        }).when(videos).saveUploadedVideo(any(), any(), any());
        assertThat(uploads.complete(started.uploadId(), USER, null)).isEqualTo(43L);
        assertThat(rows).isEmpty();
    }

    @Test
    void partsMustHaveTheExpectedLengthAndBelongToTheUser() {
        byte[] file = file(PART + 1);
        UploadSessionDto started = uploads.start(USER, CHANNEL, request(file));

        assertThatThrownBy(() -> uploads.uploadPart(started.uploadId(), USER, 2, new ByteArrayInputStream(new byte[3]), 3))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> uploads.partUrls(started.uploadId(), USER, List.of(3)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> uploads.get(started.uploadId(), 99L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void tooLargeFilesAndTooManyOpenUploadsAreRefused() {
        StartUploadRequest huge = new StartUploadRequest("big.mp4", DataSize.ofMegabytes(101).toBytes(), 1L, "video/mp4",
                "Big", LocalTime.of(0, 1), VideoVisibility.PUBLIC, null);
        assertThatThrownBy(() -> uploads.start(USER, CHANNEL, huge))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));

        for (int i = 0; i < 3; i++) {
            uploads.start(USER, CHANNEL, new StartUploadRequest("clip" + i + ".mp4", 10, 1L, "video/mp4", "Clip",
                    LocalTime.of(0, 1), VideoVisibility.PUBLIC, null));
        }
        assertThatThrownBy(() -> uploads.start(USER, CHANNEL, new StartUploadRequest("one-more.mp4", 10, 1L, "video/mp4",
                "Clip", LocalTime.of(0, 1), VideoVisibility.PUBLIC, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void discardingOrExpiringAnUploadDeletesItsParts() throws Exception {
        byte[] file = file(PART + 1);
        UploadSessionDto discarded = uploads.start(USER, CHANNEL, request(file));
        sendPart(discarded.uploadId(), file, 1);
        uploads.discard(discarded.uploadId(), USER);
        assertThat(rows).isEmpty();
        assertThat(storedFiles()).isEmpty();

        UploadSessionDto abandoned = uploads.start(USER, CHANNEL, request(file));
        sendPart(abandoned.uploadId(), file, 1);
        clock = Clock.offset(clock, Duration.ofDays(8));
        uploads = service();
        assertThat(uploads.removeExpired(100)).isEqualTo(1);
        assertThat(rows).isEmpty();
        assertThat(storedFiles()).isEmpty();
    }

    private List<Path> storedFiles() throws Exception {
        try (Stream<Path> files = Files.walk(storageDir)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void partSizeKeepsLargeFilesUnderTheS3PartLimit() throws Exception {
        var method = ResumableUploadService.class.getDeclaredMethod("partSizeFor", long.class);
        method.setAccessible(true);
        assertThat((long) method.invoke(null, 10L)).isEqualTo(PART);
        long twoHundredGb = DataSize.ofGigabytes(200).toBytes();
        long partSize = (long) method.invoke(null, twoHundredGb);
        assertThat((twoHundredGb + partSize - 1) / partSize).isLessThanOrEqualTo(10_000);
        assertThat(partSize % (1024 * 1024)).isZero();
    }
}
