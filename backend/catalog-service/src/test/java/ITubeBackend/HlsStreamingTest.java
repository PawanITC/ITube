package ITubeBackend;

import ITubeBackend.common.events.Events;
import ITubeBackend.common.events.ItubeTopics;
import ITubeBackend.dto.video.QualityLevelDto;
import ITubeBackend.dto.video.RenditionRef;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoDeletedEvent;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.service.video.VideoDeletedMessaging;
import ITubeBackend.service.video.VideoService;
import ITubeBackend.storage.HlsPlaylist;
import ITubeBackend.storage.LocalObjectStorage;
import ITubeBackend.storage.ObjectStorage;
import ITubeBackend.support.CatalogTestSupport;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Adaptive streaming: master and media playlists served by the catalog, and segments cleaned up with the video. */
class HlsStreamingTest {
    private static final String PLAYLIST_KEY = "videos/a/hls/720p/index.m3u8";
    private static final String PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.000000,
            seg_00000.m4s
            #EXTINF:4.200000,
            seg_00001.m4s
            #EXT-X-ENDLIST
            """;

    @TempDir
    Path storageDir;

    private final VideoRepository videos = mock(VideoRepository.class);
    private ObjectStorage storage;
    private VideoService service;

    @BeforeEach
    void setUp() {
        storage = new LocalObjectStorage(storageDir);
        service = CatalogTestSupport.videoService(mock(ChannelRepository.class), videos, mock(ApplicationEventPublisher.class),
                CatalogTestSupport.denyAllPayments(), null, storage, CatalogTestSupport.storageProperties(true),
                new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.systemUTC());
        when(videos.findAccess(42L)).thenReturn(Optional.of(
                new VideoAccessDto(42L, 5L, 7L, VideoVisibility.MEMBERS_ONLY, null, LocalTime.of(0, 1))));
        when(videos.findRendition(42L, "720p")).thenReturn(Optional.of(new RenditionRef(PLAYLIST_KEY, HlsPlaylist.CONTENT_TYPE)));
        when(videos.findRendition(42L, "360p")).thenReturn(Optional.of(new RenditionRef("videos/a/renditions/360p.mp4", "video/mp4")));
    }

    private void storeHls() {
        storage.put(PLAYLIST_KEY, PLAYLIST.getBytes(), HlsPlaylist.CONTENT_TYPE);
        for (String file : List.of("init.mp4", "seg_00000.m4s", "seg_00001.m4s")) {
            storage.put("videos/a/hls/720p/" + file, new byte[]{1, 2, 3}, "video/iso.segment");
        }
    }

    @Test
    void masterPlaylistListsHlsQualitiesWithBandwidthAndPassesTheTokenOn() {
        // 7.5 MB and 22.5 MB over one minute: 1 and 3 Mbit/s on average
        when(videos.findQualityLevels(42L)).thenReturn(List.of(
                new QualityLevelDto("360p", 7_500_000L, QualityLevelDto.HLS),
                new QualityLevelDto("720p", 22_500_000L, QualityLevelDto.HLS),
                new QualityLevelDto("1080p", 50_000_000L, QualityLevelDto.MP4)));

        String master = service.hlsMasterPlaylist(42L, "a+b");

        assertThat(master).startsWith("#EXTM3U\n")
                .contains("#EXT-X-STREAM-INF:BANDWIDTH=1300000,AVERAGE-BANDWIDTH=1000000,NAME=\"360p\"\n"
                        + "/api/videos/42/hls/360p/index.m3u8?token=a%2Bb\n")
                .contains("AVERAGE-BANDWIDTH=3000000,NAME=\"720p\"\n/api/videos/42/hls/720p/index.m3u8?token=a%2Bb\n")
                .doesNotContain("1080p");
    }

    @Test
    void noHlsQualitiesYetIsNotFound() {
        when(videos.findQualityLevels(42L)).thenReturn(List.of(new QualityLevelDto("360p", 1L, QualityLevelDto.MP4)));
        assertThatThrownBy(() -> service.hlsMasterPlaylist(42L, null)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void mediaPlaylistPointsEverySegmentAtTheApiWithTheToken() {
        storeHls();

        String playlist = service.hlsMediaPlaylist(42L, "720p", "tok");

        assertThat(playlist)
                .contains("#EXT-X-MAP:URI=\"/api/videos/42/hls/720p/init.mp4?token=tok\"")
                .contains("\n/api/videos/42/hls/720p/seg_00000.m4s?token=tok\n")
                .contains("\n/api/videos/42/hls/720p/seg_00001.m4s?token=tok\n")
                .contains("#EXTINF:4.200000,").contains("#EXT-X-ENDLIST");
    }

    @Test
    void segmentKeysStayInsideTheirQualityFolder() {
        assertThat(service.getHlsSegmentKey(42L, "720p", "seg_00001.m4s")).isEqualTo("videos/a/hls/720p/seg_00001.m4s");
        assertThat(service.getHlsSegmentKey(42L, "720p", "init.mp4")).isEqualTo("videos/a/hls/720p/init.mp4");
        for (String bad : List.of("../source", "index.m3u8", "seg.m4s/x", "thumbnail-1")) {
            assertThatThrownBy(() -> service.getHlsSegmentKey(42L, "720p", bad)).isInstanceOf(ResponseStatusException.class);
        }
        // A legacy MP4 rendition has no segments
        assertThatThrownBy(() -> service.getHlsSegmentKey(42L, "360p", "seg_00000.m4s")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.getHlsSegmentKey(42L, "../720p", "seg_00000.m4s")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletingAVideoRemovesEverySegmentOfItsPlaylists() {
        storeHls();
        storage.put("videos/a/source", new byte[]{9}, "video/mp4");
        ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
        VideoDeletedMessaging cleanup = new VideoDeletedMessaging(mock(KafkaTemplate.class), json, storage, mock(ItubeTopics.class));

        cleanup.removeStoredFiles(Events.toJson(json, new VideoDeletedEvent(42L, List.of(PLAYLIST_KEY, "videos/a/source"))));

        for (String key : List.of(PLAYLIST_KEY, "videos/a/hls/720p/init.mp4", "videos/a/hls/720p/seg_00000.m4s",
                "videos/a/hls/720p/seg_00001.m4s", "videos/a/source")) {
            assertThat(storage.stat(key)).as(key).isEmpty();
        }
    }

    @Test
    void playlistParsingFindsInitAndMediaSegmentsAndKeepsEverythingElse() {
        assertThat(HlsPlaylist.referencedFiles(PLAYLIST)).containsExactly("init.mp4", "seg_00000.m4s", "seg_00001.m4s");
        assertThat(HlsPlaylist.rewrite(PLAYLIST, file -> file)).isEqualTo(PLAYLIST);
    }
}
