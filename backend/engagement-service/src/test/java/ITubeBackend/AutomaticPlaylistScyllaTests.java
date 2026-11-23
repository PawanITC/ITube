package ITubeBackend;

import ITubeBackend.engagement.AutomaticPlaylistStore;
import ITubeBackend.engagement.AutomaticPlaylistStore.Snapshot;
import ITubeBackend.engagement.scylla.ScyllaAutomaticPlaylistStore;
import ITubeBackend.engagement.scylla.ScyllaProperties;
import ITubeBackend.engagement.scylla.ScyllaSchema;
import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "itube.test.scylla", matches = "true")
class AutomaticPlaylistScyllaTests {
    @Test
    void playbackOrderPersistsOnlyForItsOwnerAndHasABoundedLifetime() {
        String keyspace = "itube_auto_test_" + Long.toUnsignedString(System.nanoTime());
        try (var session = CqlSession.builder().addContactPoint(new InetSocketAddress("127.0.0.1", 9042)).withLocalDatacenter("datacenter1").build()) {
            try {
                ScyllaSchema.apply(session, new ScyllaProperties(null, null, keyspace, null, null, null, true, null));
                var store = new ScyllaAutomaticPlaylistStore(session, keyspace);
                UUID id = UUID.randomUUID();
                store.save(new Snapshot(id, 1L, Instant.parse("2026-11-22T12:00:00Z"), true, List.of(30L, 10L, 20L)));
                var reopened = new ScyllaAutomaticPlaylistStore(session, keyspace);
                assertThat(reopened.find(1L, id).orElseThrow().videoIds()).containsExactly(30L, 10L, 20L);
                assertThat(reopened.find(2L, id)).isEmpty();
                var row = session.execute("SELECT TTL(generated_at) AS remaining FROM " + keyspace
                        + ".automatic_playlist_snapshots WHERE user_id = 1 AND snapshot_id = " + id).one();
                assertThat(row.getInt("remaining")).isBetween(1, AutomaticPlaylistStore.TTL_SECONDS);
                UUID empty = UUID.randomUUID();
                store.save(new Snapshot(empty, 1L, Instant.now(), false, List.of()));
                assertThat(reopened.find(1L, empty).orElseThrow().videoIds()).isEmpty();
            } finally { session.execute(com.datastax.oss.driver.api.core.cql.SimpleStatement.newInstance("DROP KEYSPACE IF EXISTS " + keyspace).setTimeout(java.time.Duration.ofSeconds(60))); }
        }
    }
}
