package ITubeBackend;

import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.service.video.VideoService;
import ITubeBackend.storage.LocalObjectStorage;
import ITubeBackend.support.CatalogTestSupport;
import org.junit.jupiter.api.BeforeEach;
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
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A video or ad's view count goes up once per viewer, however often they watch it. */
class UniqueViewsTest {
    @TempDir
    Path storageDir;

    private final VideoRepository videos = mock(VideoRepository.class);
    private VideoService service;

    @BeforeEach
    void setUp() {
        service = CatalogTestSupport.videoService(mock(ChannelRepository.class), videos, mock(ApplicationEventPublisher.class),
                CatalogTestSupport.denyAllPayments(), null, new LocalObjectStorage(storageDir), CatalogTestSupport.storageProperties(true),
                new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.systemUTC());
        when(videos.findAccess(42L)).thenReturn(Optional.of(
                new VideoAccessDto(42L, 5L, 7L, VideoVisibility.PUBLIC, null, LocalTime.of(0, 1))));
    }

    @Test
    void aNewViewerIsCountedAndARepeatViewerIsNot() {
        when(videos.recordViewer(42L, "user:9")).thenReturn(1, 0);

        assertThat(service.recordView(42L, 9L, "ignored-when-signed-in")).isTrue();
        assertThat(service.recordView(42L, 9L, null)).isFalse();

        verify(videos, org.mockito.Mockito.times(1)).incrementViewCount(42L);
    }

    @Test
    void twoRacingRequestsFromOneViewerCountOnce() {
        when(videos.recordViewer(42L, "user:9")).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        assertThat(service.recordView(42L, 9L, null)).isFalse();
        verify(videos, never()).incrementViewCount(any());
    }

    @Test
    void signedOutViewersAreRecognisedByTheirBrowserId() {
        when(videos.recordViewer(42L, "anon:3f2a9c1e-5b7d-4e8f-9a0b-1c2d3e4f5a6b")).thenReturn(1);

        assertThat(service.recordView(42L, null, "3f2a9c1e-5b7d-4e8f-9a0b-1c2d3e4f5a6b")).isTrue();
        verify(videos).incrementViewCount(42L);
    }

    @Test
    void withoutAUsableViewerIdNothingIsCounted() {
        for (String bad : new String[]{null, "", "short", "has spaces in it", "x".repeat(80), "semi;colon-id-1234"}) {
            assertThat(service.recordView(42L, null, bad)).as(String.valueOf(bad)).isFalse();
        }
        verify(videos, never()).recordViewer(any(), anyString());
        verify(videos, never()).incrementViewCount(any());
    }

    /** Runs the repository's real insert against the real schema (H2 in PostgreSQL mode, as the h2 profile uses). */
    @Test
    void theViewerTableKeepsOneRowPerViewer() throws Exception {
        String sql = VideoRepository.class.getMethod("recordViewer", Long.class, String.class)
                .getAnnotation(Query.class).value().replace(":videoId", "?").replace(":viewerKey", "?");
        try (Connection db = DriverManager.getConnection(
                "jdbc:h2:mem:unique-views;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
             Statement statement = db.createStatement()) {
            statement.execute("RUNSCRIPT FROM 'classpath:schema-catalog.sql'");
            statement.execute("insert into catalog.channel (channel_id, user_id, channel_name) values (1, 7, 'c')");
            statement.execute("insert into catalog.video (video_id, channel_id, title, duration) values (10, 1, 'v', '00:01:00')");
            try (PreparedStatement insert = db.prepareStatement(sql)) {
                assertThat(record(insert, "user:9")).isEqualTo(1);
                assertThat(record(insert, "user:9")).isZero();
                assertThat(record(insert, "anon:abcdef12")).isEqualTo(1);
            }
            // Deleting the video removes its viewers too
            statement.execute("delete from catalog.video where video_id = 10");
            try (var rows = statement.executeQuery("select count(*) from catalog.video_view")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private static int record(PreparedStatement insert, String viewerKey) throws Exception {
        // :videoId and :viewerKey each appear twice in the query
        insert.setLong(1, 10L);
        insert.setString(2, viewerKey);
        insert.setLong(3, 10L);
        insert.setString(4, viewerKey);
        return insert.executeUpdate();
    }
}
