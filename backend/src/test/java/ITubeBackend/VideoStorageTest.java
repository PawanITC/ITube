package ITubeBackend;

import ITubeBackend.dto.video.CreateVideo;
import ITubeBackend.dto.video.MediaUpload;
import ITubeBackend.dto.video.VideoDeletedEvent;
import ITubeBackend.dto.video.VideoMediaDto;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoUploadedEvent;
import ITubeBackend.engagement.TimeUuids;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.entities.VideoEntity;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.service.video.VideoService;
import ITubeBackend.storage.LocalObjectStorage;
import ITubeBackend.storage.MediaKeys;
import ITubeBackend.storage.ObjectStorage;
import ITubeBackend.storage.StorageProperties;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Video files go to object storage, not Postgres; playback is served from there. */
class VideoStorageTest {
    private static final long OWNER = 7L;

    @TempDir
    Path storageDir;

    private final ChannelRepository channels = mock(ChannelRepository.class);
    private final VideoRepository videos = mock(VideoRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TransactionTemplate transactions = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalObjectStorage(storageDir);
        ChannelEntity channel = new ChannelEntity();
        channel.setOwnerUserId(OWNER);
        when(channels.existsById(5L)).thenReturn(true);
        when(channels.findById(5L)).thenReturn(Optional.of(channel));
    }

    private VideoService service(ObjectStorage store, boolean redirect) {
        StorageProperties properties = new StorageProperties("local", null, null, null, null, null, null,
                false, false, null, Duration.ofHours(1), redirect);
        return new VideoService(channels, videos, events, null, null, store, properties, transactions);
    }

    private static CreateVideo upload(byte[] video, byte[] thumbnail) {
        return new CreateVideo(5L,
                new MediaUpload(new MockMultipartFile("file", video), video.length, "video/mp4"),
                thumbnail == null ? null : new MediaUpload(new MockMultipartFile("thumbnail", thumbnail), thumbnail.length, "image/png"),
                "Title", LocalTime.of(0, 1), VideoVisibility.PUBLIC, null);
    }

    @Test
    void uploadStoresFilesInObjectStorageAndOnlyKeysInTheDatabase() {
        when(videos.save(any())).thenAnswer(call -> {
            VideoEntity video = call.getArgument(0);
            ReflectionTestUtils.setField(video, "videoId", 42L);
            return video;
        });

        Long id = service(storage, false).saveVideo(upload(new byte[]{1, 2, 3, 4}, new byte[]{9}));

        ArgumentCaptor<VideoEntity> saved = ArgumentCaptor.forClass(VideoEntity.class);
        verify(videos).save(saved.capture());
        VideoEntity video = saved.getValue();
        assertThat(id).isEqualTo(42L);
        assertThat(video.getVideoKey()).matches("videos/[0-9a-f-]{36}/source");
        assertThat(video.getVideoSizeBytes()).isEqualTo(4L);
        assertThat(video.getThumbnailKey()).startsWith(MediaKeys.folderOf(video.getVideoKey()) + "thumbnail-");
        assertThat(storage.readRange(video.getVideoKey(), 0, 10)).containsExactly(1, 2, 3, 4);
        assertThat(storage.stat(video.getThumbnailKey())).hasValueSatisfying(info -> assertThat(info.contentType()).isEqualTo("image/png"));
        verify(events).publishEvent(new VideoUploadedEvent(42L));
    }

    @Test
    void failedInsertRemovesTheUploadedFiles() {
        when(videos.save(any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service(storage, false).saveVideo(upload(new byte[]{1, 2}, new byte[]{3})))
                .hasMessageContaining("database down");

        try (var files = java.nio.file.Files.walk(storageDir)) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile)).isEmpty();
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    @Test
    void storesThatCantPreSignStreamOneBoundedRangePerRequest() {
        storage.put("videos/x/source", new byte[]{10, 11, 12, 13, 14}, "video/mp4");

        var whole = service(storage, true).serveMedia("videos/x/source", null, true);
        var range = service(storage, true).serveMedia("videos/x/source", "bytes=1-3", false);

        assertThat(whole.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(whole.getBody()).containsExactly(10, 11, 12, 13, 14);
        assertThat(range.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(range.getBody()).containsExactly(11, 12, 13);
        assertThat(range.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 1-3/5");
        assertThat(range.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void playbackRedirectsToAPreSignedUrlSoBytesBypassTheBackend() {
        ObjectStorage s3 = mock(ObjectStorage.class);
        when(s3.presignedGet("videos/x/source", Duration.ofHours(1)))
                .thenReturn(Optional.of(URI.create("https://cdn.example/videos/x/source?X-Amz-Signature=abc")));

        var paid = service(s3, true).serveMedia("videos/x/source", "bytes=0-", false);
        var open = service(s3, true).serveMedia("videos/x/source", null, true);

        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(paid.getHeaders().getLocation()).hasHost("cdn.example");
        assertThat(paid.getHeaders().getCacheControl()).contains("no-store");
        assertThat(open.getHeaders().getCacheControl()).contains("public");
    }

    @Test
    void deletingAVideoAnnouncesEveryStoredFileForCleanup() {
        when(videos.findAccess(42L)).thenReturn(Optional.of(new VideoAccessDto(42L, 5L, OWNER, VideoVisibility.PUBLIC, null, LocalTime.of(0, 1))));
        when(videos.findRenditionKeys(42L)).thenReturn(List.of("videos/a/renditions/360p.mp4"));
        when(videos.findMedia(42L)).thenReturn(Optional.of(new VideoMediaDto("videos/a/source", "video/mp4", "videos/a/thumbnail-1", "image/png")));

        service(storage, true).deleteVideo(42L, OWNER);

        verify(videos).deleteVideoById(42L);
        verify(events).publishEvent(new VideoDeletedEvent(42L,
                List.of("videos/a/renditions/360p.mp4", "videos/a/source", "videos/a/thumbnail-1")));
    }

    @Test
    void timeUuidsCarryTheirTimeAndAreRepeatableForCopies() {
        Instant time = Instant.parse("2026-01-02T10:00:00.123Z");
        var id = TimeUuids.at(time, 99L);
        assertThat(id.version()).isEqualTo(1);
        assertThat(Uuids.unixTimestamp(id)).isEqualTo(time.toEpochMilli());
        assertThat(TimeUuids.at(time, 99L)).isEqualTo(id);
        assertThat(TimeUuids.at(time, 100L)).isNotEqualTo(id);
        assertThat(TimeUuids.at(Instant.now()).version()).isEqualTo(1);
    }

    @Test
    void storageKeysCannotEscapeTheStorageFolder() {
        assertThatThrownBy(() -> storage.put("../outside", new byte[]{1}, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
