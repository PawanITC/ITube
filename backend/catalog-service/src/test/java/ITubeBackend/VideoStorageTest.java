package ITubeBackend;

import ITubeBackend.dto.video.AdBreakDto;
import ITubeBackend.dto.video.AdCandidate;
import ITubeBackend.dto.video.CreateVideo;
import ITubeBackend.dto.video.MediaUpload;
import ITubeBackend.dto.video.VideoDeletedEvent;
import ITubeBackend.dto.video.VideoMediaDto;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoUploadedEvent;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.entities.VideoEntity;
import ITubeBackend.entities.VideoType;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.support.CatalogTestSupport;
import ITubeBackend.service.video.VideoService;
import ITubeBackend.storage.LocalObjectStorage;
import ITubeBackend.storage.MediaKeys;
import ITubeBackend.storage.ObjectStorage;
import ITubeBackend.storage.StorageProperties;
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
        return CatalogTestSupport.videoService(channels, videos, events, CatalogTestSupport.denyAllPayments(), null, store,
                CatalogTestSupport.storageProperties(redirect), transactions, java.time.Clock.systemUTC());
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
        verify(events).publishEvent(new VideoUploadedEvent(42L, video.getVideoKey()));
        // The new id comes from the shard's allocator (it encodes the shard), not from the database
        assertThat(video.getVideoId()).isEqualTo(CatalogTestSupport.NEXT_VIDEO_ID);
    }

    private static CreateVideo ad(LocalTime duration, boolean adsEnabled) {
        byte[] bytes = {1, 2};
        return new CreateVideo(5L, new MediaUpload(new MockMultipartFile("file", bytes), bytes.length, "video/mp4"), null,
                "Ad", duration, VideoVisibility.PUBLIC, null, VideoType.AD, adsEnabled, null);
    }

    @Test
    void adsLongerThanOneMinuteAreRefusedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> service(storage, false).saveVideo(ad(LocalTime.of(0, 1, 1), false)))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).isEqualTo("Ads can be at most 1 minute long");
                });
        verify(videos, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void aOneMinuteAdIsSavedAsAnAdThatNeverShowsAds() {
        when(videos.save(any())).thenAnswer(call -> call.getArgument(0));

        service(storage, false).saveVideo(ad(LocalTime.of(0, 1), true));

        ArgumentCaptor<VideoEntity> saved = ArgumentCaptor.forClass(VideoEntity.class);
        verify(videos).save(saved.capture());
        assertThat(saved.getValue().getVideoType()).isEqualTo(VideoType.AD);
        assertThat(saved.getValue().isAdsEnabled()).isFalse();
    }

    @Test
    void normalVideosKeepTheirAdsChoiceAndMayBeLongerThanAMinute() {
        when(videos.save(any())).thenAnswer(call -> call.getArgument(0));
        byte[] bytes = {1, 2};

        service(storage, false).saveVideo(new CreateVideo(5L,
                new MediaUpload(new MockMultipartFile("file", bytes), bytes.length, "video/mp4"), null,
                "Long video", LocalTime.of(0, 10), VideoVisibility.PUBLIC, null, VideoType.VIDEO, true, null));

        ArgumentCaptor<VideoEntity> saved = ArgumentCaptor.forClass(VideoEntity.class);
        verify(videos).save(saved.capture());
        assertThat(saved.getValue().getVideoType()).isEqualTo(VideoType.VIDEO);
        assertThat(saved.getValue().isAdsEnabled()).isTrue();
    }

    @Test
    void aVideoWithAdsEnabledGetsOneAdAtItsMidpoint() {
        when(videos.findAccess(42L)).thenReturn(Optional.of(new VideoAccessDto(42L, 5L, OWNER, VideoVisibility.PUBLIC, null, LocalTime.of(0, 10))));
        when(videos.findAdCount(42L)).thenReturn(Optional.of(1));
        when(videos.findAdCandidates(any())).thenReturn(List.of(new AdCandidate(43L, "Buy this", "Brand", LocalTime.of(0, 0, 30))));

        assertThat(service(storage, false).adBreaks(42L, null)).containsExactly(
                new AdBreakDto(0.5, 43L, "Buy this", "Brand", "/api/videos/43/file", 30));
    }

    @Test
    void noAdsPlayWhenTheCreatorDisabledThemOrNoAdsExist() {
        when(videos.findAccess(42L)).thenReturn(Optional.of(new VideoAccessDto(42L, 5L, OWNER, VideoVisibility.PUBLIC, null, LocalTime.of(0, 10))));
        when(videos.findAdCandidates(any())).thenReturn(List.of(new AdCandidate(43L, "Buy this", "Brand", LocalTime.of(0, 0, 30))));
        when(videos.findAdCount(42L)).thenReturn(Optional.of(0));
        assertThat(service(storage, false).adBreaks(42L, null)).isEmpty();

        when(videos.findAdCount(42L)).thenReturn(Optional.of(1));
        when(videos.findAdCandidates(any())).thenReturn(List.of());
        assertThat(service(storage, false).adBreaks(42L, null)).isEmpty();
    }

    @Test
    void videosOfOneMinuteOrLessNeverGetAds() {
        when(videos.findAdCount(42L)).thenReturn(Optional.of(1));
        when(videos.findAdCandidates(any())).thenReturn(List.of(new AdCandidate(43L, "Buy this", "Brand", LocalTime.of(0, 0, 30))));

        when(videos.findAccess(42L)).thenReturn(Optional.of(new VideoAccessDto(42L, 5L, OWNER, VideoVisibility.PUBLIC, null, LocalTime.of(0, 1))));
        assertThat(service(storage, false).adBreaks(42L, null)).isEmpty();

        when(videos.findAccess(42L)).thenReturn(Optional.of(new VideoAccessDto(42L, 5L, OWNER, VideoVisibility.PUBLIC, null, LocalTime.of(0, 1, 1))));
        assertThat(service(storage, false).adBreaks(42L, null)).hasSize(1);
    }

    @Test
    void severalAdsAreSpreadEvenlyAndAreDifferentAdsWherePossible() {
        when(videos.findAccess(42L)).thenReturn(Optional.of(new VideoAccessDto(42L, 5L, OWNER, VideoVisibility.PUBLIC, null, LocalTime.of(0, 25))));
        when(videos.findAdCount(42L)).thenReturn(Optional.of(2));
        when(videos.findAdCandidates(any())).thenReturn(List.of(
                new AdCandidate(43L, "Buy this", "Brand", LocalTime.of(0, 0, 30)),
                new AdCandidate(44L, "Buy that", "Brand", LocalTime.of(0, 0, 20))));

        List<AdBreakDto> breaks = service(storage, false).adBreaks(42L, null);

        assertThat(breaks).extracting(AdBreakDto::position).containsExactly(1.0 / 3, 2.0 / 3);
        assertThat(breaks).extracting(AdBreakDto::adVideoId).containsExactlyInAnyOrder(43L, 44L);
    }

    @Test
    void aStoredAdCountAboveOnePerTenMinutesIsCapped() {
        when(videos.findAccess(42L)).thenReturn(Optional.of(new VideoAccessDto(42L, 5L, OWNER, VideoVisibility.PUBLIC, null, LocalTime.of(0, 19, 59))));
        when(videos.findAdCount(42L)).thenReturn(Optional.of(3));
        when(videos.findAdCandidates(any())).thenReturn(List.of(new AdCandidate(43L, "Buy this", "Brand", LocalTime.of(0, 0, 30))));

        assertThat(service(storage, false).adBreaks(42L, null)).extracting(AdBreakDto::position).containsExactly(0.5);
    }

    @Test
    void uploadsMayChooseUpToOneAdPerTenMinutes() {
        assertThat(VideoService.maxAdsFor(LocalTime.of(0, 5))).isEqualTo(1);
        assertThat(VideoService.maxAdsFor(LocalTime.of(0, 19, 59))).isEqualTo(1);
        assertThat(VideoService.maxAdsFor(LocalTime.of(0, 20))).isEqualTo(2);
        assertThat(VideoService.maxAdsFor(LocalTime.of(1, 0))).isEqualTo(6);
        when(videos.save(any())).thenAnswer(call -> call.getArgument(0));
        byte[] bytes = {1, 2};

        assertThatThrownBy(() -> service(storage, false).saveVideo(new CreateVideo(5L,
                new MediaUpload(new MockMultipartFile("file", bytes), bytes.length, "video/mp4"), null,
                "Long video", LocalTime.of(0, 25), VideoVisibility.PUBLIC, null, VideoType.VIDEO, true, 3)))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).isEqualTo("This video can have between 1 and 2 ads (one per 10 minutes of video)");
                });

        service(storage, false).saveVideo(new CreateVideo(5L,
                new MediaUpload(new MockMultipartFile("file", bytes), bytes.length, "video/mp4"), null,
                "Long video", LocalTime.of(0, 25), VideoVisibility.PUBLIC, null, VideoType.VIDEO, true, 2));
        ArgumentCaptor<VideoEntity> saved = ArgumentCaptor.forClass(VideoEntity.class);
        verify(videos).save(saved.capture());
        assertThat(saved.getValue().getAdCount()).isEqualTo(2);
    }

    @Test
    void adBreaksOfSomeoneElsesPrivateVideoAreNotFound() {
        when(videos.findAccess(42L)).thenReturn(Optional.of(new VideoAccessDto(42L, 5L, OWNER, VideoVisibility.PRIVATE, null, LocalTime.of(0, 10))));
        assertThatThrownBy(() -> service(storage, false).adBreaks(42L, 99L))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
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

        var whole = service(storage, true).serveMedia("videos/x/source", null, true, true);
        var range = service(storage, true).serveMedia("videos/x/source", "bytes=1-3", false, true);

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

        var paid = service(s3, true).serveMedia("videos/x/source", "bytes=0-", false, true);
        var open = service(s3, true).serveMedia("videos/x/source", null, true, true);

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
    void storageKeysCannotEscapeTheStorageFolder() {
        assertThatThrownBy(() -> storage.put("../outside", new byte[]{1}, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
