package ITubeBackend;

import ITubeBackend.dto.video.RenditionRef;
import ITubeBackend.dto.video.VideoTranscodedEvent;
import ITubeBackend.entities.VideoEntity;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.service.video.HlsBackfillJob;
import ITubeBackend.service.video.VideoService;
import ITubeBackend.service.video.VideoTranscodeMessaging;
import ITubeBackend.storage.HlsPlaylist;
import ITubeBackend.storage.LocalObjectStorage;
import ITubeBackend.storage.ObjectStorage;
import ITubeBackend.support.CatalogTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Videos from before HLS are re-transcoded in the background and switched over without ever becoming unplayable. */
class HlsBackfillTest {
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path storageDir;

    private final VideoRepository videos = mock(VideoRepository.class);
    private final TransactionTemplate transactions = new TransactionTemplate(mock(PlatformTransactionManager.class));

    private static VideoEntity video(long id, String key, String status) {
        VideoEntity video = new VideoEntity(id);
        video.setVideoKey(key);
        video.setProcessingStatus(status);
        return video;
    }

    @Test
    void queuesABatchOfOlderVideosAndCountsTheAttempt() {
        VideoTranscodeMessaging messaging = mock(VideoTranscodeMessaging.class);
        VideoEntity older = video(42L, "videos/a/source", "READY");
        when(videos.findHlsBackfillCandidates(3, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(2), 10))
                .thenReturn(List.of(42L));
        when(videos.findAllById(List.of(42L))).thenReturn(List.of(older));

        new HlsBackfillJob(CatalogTestSupport.singleShard(), videos, messaging, transactions, CLOCK, true, 10,
                java.time.Duration.ofHours(2), 3).run();

        verify(messaging).send(42L, "videos/a/source");
        assertThat(older.getHlsBackfillAttempts()).isEqualTo(1);
        assertThat(older.getHlsBackfillRequestedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void doesNothingWhenDisabled() {
        VideoTranscodeMessaging messaging = mock(VideoTranscodeMessaging.class);
        new HlsBackfillJob(CatalogTestSupport.singleShard(), videos, messaging, transactions, CLOCK, false, 10,
                java.time.Duration.ofHours(2), 3).run();
        verify(videos, never()).findHlsBackfillCandidates(anyInt(), any(), anyInt());
        verify(messaging, never()).send(any(), any());
    }

    private VideoService service(ObjectStorage storage) {
        return CatalogTestSupport.videoService(mock(ChannelRepository.class), videos, mock(ApplicationEventPublisher.class),
                CatalogTestSupport.denyAllPayments(), null, storage, CatalogTestSupport.storageProperties(true),
                transactions, CLOCK);
    }

    @Test
    void theHlsResultReplacesTheOldMp4FileAndDeletesIt() {
        ObjectStorage storage = new LocalObjectStorage(storageDir);
        storage.put("videos/a/renditions/720p.mp4", new byte[]{1}, "video/mp4");
        VideoEntity ready = video(42L, "videos/a/source", "READY");
        when(videos.findById(42L)).thenReturn(Optional.of(ready));
        when(videos.findRendition(42L, "720p")).thenReturn(Optional.of(new RenditionRef("videos/a/renditions/720p.mp4", "video/mp4")));

        service(storage).applyTranscodeResult(new VideoTranscodedEvent(42L, VideoTranscodedEvent.READY,
                List.of(new VideoTranscodedEvent.Rendition("720p", "videos/a/hls/720p/index.m3u8", 1234L))));

        verify(videos).saveRendition(42L, "720p", HlsPlaylist.CONTENT_TYPE, "videos/a/hls/720p/index.m3u8", 1234L);
        assertThat(ready.getProcessingStatus()).isEqualTo("READY");
        assertThat(storage.stat("videos/a/renditions/720p.mp4")).isEmpty();
    }

    @Test
    void aFailedReTranscodeLeavesAPlayingVideoReady() {
        VideoEntity ready = video(42L, "videos/a/source", "READY");
        when(videos.findById(42L)).thenReturn(Optional.of(ready));

        service(new LocalObjectStorage(storageDir)).applyTranscodeResult(
                new VideoTranscodedEvent(42L, VideoTranscodedEvent.FAILED, List.of()));

        assertThat(ready.getProcessingStatus()).isEqualTo("READY");
    }

    @Test
    void aFailedFirstTranscodeStillMarksTheVideoFailed() {
        VideoEntity processing = video(43L, "videos/b/source", "PROCESSING");
        when(videos.findById(43L)).thenReturn(Optional.of(processing));

        service(new LocalObjectStorage(storageDir)).applyTranscodeResult(
                new VideoTranscodedEvent(43L, VideoTranscodedEvent.FAILED, List.of()));

        assertThat(processing.getProcessingStatus()).isEqualTo("FAILED");
    }

    /** Runs the repository's real SQL against the real schema (H2 in PostgreSQL mode, as the h2 profile uses). */
    @Test
    void candidateQueryPicksOnlyReadyVideosWithoutHls() throws Exception {
        String sql = VideoRepository.class.getMethod("findHlsBackfillCandidates", int.class, OffsetDateTime.class, int.class)
                .getAnnotation(Query.class).value()
                .replace(":maxAttempts", "?").replace(":retryBefore", "?").replace(":batchSize", "?");
        try (Connection db = DriverManager.getConnection(
                "jdbc:h2:mem:hls-backfill;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
             Statement statement = db.createStatement()) {
            statement.execute("RUNSCRIPT FROM 'classpath:schema-catalog.sql'");
            statement.execute("insert into catalog.channel (channel_id, user_id, channel_name) values (1, 7, 'c')");
            String insert = "insert into catalog.video (video_id, channel_id, video_key, title, duration, processing_status, "
                    + "hls_backfill_attempts, hls_backfill_requested_at) values ";
            statement.execute(insert + "(10, 1, 'videos/a/source', 'mp4 only', '00:01:00', 'READY', 0, null)");
            statement.execute(insert + "(11, 1, 'videos/b/source', 'no renditions', '00:01:00', 'READY', 0, null)");
            statement.execute(insert + "(12, 1, 'videos/c/source', 'already hls', '00:01:00', 'READY', 0, null)");
            statement.execute(insert + "(13, 1, 'videos/d/source', 'processing', '00:01:00', 'PROCESSING', 0, null)");
            statement.execute(insert + "(14, 1, 'videos/e/source', 'given up', '00:01:00', 'READY', 3, null)");
            statement.execute(insert + "(15, 1, 'videos/f/source', 'queued recently', '00:01:00', 'READY', 1, TIMESTAMP WITH TIME ZONE '2026-11-23 11:30:00+00')");
            statement.execute(insert + "(16, 1, 'videos/g/source', 'queued long ago', '00:01:00', 'READY', 1, TIMESTAMP WITH TIME ZONE '2026-11-23 09:00:00+00')");
            statement.execute(insert + "(17, 1, null, 'no source', '00:01:00', 'READY', 0, null)");
            statement.execute("insert into catalog.video_rendition (video_id, quality, storage_key) values "
                    + "(10, '720p', 'videos/a/renditions/720p.mp4'), (12, '720p', 'videos/c/hls/720p/index.m3u8')");

            List<Long> found = new ArrayList<>();
            try (PreparedStatement query = db.prepareStatement(sql)) {
                query.setInt(1, 3);
                query.setObject(2, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(2));
                query.setInt(3, 10);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) found.add(rows.getLong(1));
                }
            }
            assertThat(found).containsExactly(10L, 11L, 16L);
        }
    }
}
