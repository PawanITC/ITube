package ITubeBackend;

import ITubeBackend.engagement.PlaylistStore.Item;
import ITubeBackend.engagement.PlaylistStore.Playlist;
import ITubeBackend.engagement.TimeUuids;
import ITubeBackend.engagement.scylla.ScyllaPlaylistStore;
import ITubeBackend.engagement.scylla.ScyllaProperties;
import ITubeBackend.engagement.scylla.ScyllaSchema;
import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "itube.test.scylla", matches = "true")
class PlaylistScyllaTests {
    @Test
    void playlistsPersistAndConflictingEditsCannotOverwriteOrResurrectDeletedRows() throws Exception {
        String keyspace = "itube_playlist_test_" + Long.toUnsignedString(System.nanoTime());
        try (var session = CqlSession.builder().addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
                .withLocalDatacenter("datacenter1").build()) {
            try {
                ScyllaSchema.apply(session, new ScyllaProperties(null, null, keyspace, null, null, null, true, null));
                var store = new ScyllaPlaylistStore(session, keyspace);
                Instant time = Instant.parse("2026-11-22T10:00:00Z");
                var original = new Playlist(TimeUuids.at(time), 1L, "Learning", time, time, UUID.randomUUID(), List.of());
                assertThat(store.create(original)).isTrue();
                assertThat(store.create(original)).isFalse();
                assertThat(store.find(1L, original.id()).orElseThrow().items()).isEmpty();
                assertThat(store.find(2L, original.id())).isEmpty();
                var first = new Playlist(original.id(), 1L, "First edit", time, time, UUID.randomUUID(), List.of(new Item(10L, time), new Item(20L, time)));
                var second = new Playlist(original.id(), 1L, "Second edit", time, time, UUID.randomUUID(), List.of(new Item(30L, time)));
                try (var executor = Executors.newFixedThreadPool(2)) {
                    var left = executor.submit(() -> store.replace(original, first));
                    var right = executor.submit(() -> store.replace(original, second));
                    assertThat((left.get() ? 1 : 0) + (right.get() ? 1 : 0)).isEqualTo(1);
                }
                var reopened = new ScyllaPlaylistStore(session, keyspace);
                var current = reopened.find(1L, original.id()).orElseThrow();
                var reordered = new Playlist(current.id(), 1L, "Ordered", time, time, UUID.randomUUID(), List.of(new Item(20L, time), new Item(10L, time)));
                assertThat(reopened.replace(current, reordered)).isTrue();
                assertThat(reopened.find(1L, original.id()).orElseThrow().items()).extracting(Item::videoId).containsExactly(20L, 10L);
                assertThat(reopened.delete(original)).isFalse();
                assertThat(reopened.delete(reordered)).isTrue();
                assertThat(reopened.replace(reordered, first)).isFalse();
                assertThat(reopened.find(1L, original.id())).isEmpty();
                try (var rows = reopened.newestFirst(1L)) { assertThat(rows.toList()).isEmpty(); }
            } finally {
                session.execute(com.datastax.oss.driver.api.core.cql.SimpleStatement.newInstance("DROP KEYSPACE IF EXISTS " + keyspace).setTimeout(java.time.Duration.ofSeconds(60)));
            }
        }
    }
}
