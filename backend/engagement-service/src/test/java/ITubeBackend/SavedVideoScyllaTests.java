package ITubeBackend;

import ITubeBackend.engagement.scylla.ScyllaProperties;
import ITubeBackend.engagement.scylla.ScyllaSavedVideoStore;
import ITubeBackend.engagement.scylla.ScyllaSchema;
import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.InetSocketAddress;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

/** Optional real-database check: start compose's scylla service and pass -Ditube.test.scylla=true. */
@EnabledIfSystemProperty(named = "itube.test.scylla", matches = "true")
class SavedVideoScyllaTests {
    @Test
    void savedVideosPersistWithUniqueEntriesStableOrderingAndIsolatedRemoval() {
        String keyspace = "itube_saved_test_" + Long.toUnsignedString(System.nanoTime());
        try (var session = CqlSession.builder().addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
                .withLocalDatacenter("datacenter1").build()) {
            try {
                ScyllaSchema.apply(session, new ScyllaProperties(null, null, keyspace, null, null, null, true, null));
                var store = new ScyllaSavedVideoStore(session, keyspace);
                Instant older = Instant.parse("2026-11-22T10:00:00Z");
                Instant newer = older.plusSeconds(60);
                assertThat(store.saveIfAbsent(1L, 10L, older)).isTrue();
                assertThat(store.saveIfAbsent(1L, 10L, newer)).isFalse();
                store.saveIfAbsent(1L, 20L, newer);
                store.saveIfAbsent(1L, 30L, newer);
                store.saveIfAbsent(2L, 10L, older);
                // A new store instance reads persisted data rather than any Java-side state.
                var reopened = new ScyllaSavedVideoStore(session, keyspace);
                try (var rows = reopened.newestFirst(1L)) {
                    assertThat(rows.toList()).extracting(row -> row.videoId()).containsExactly(30L, 20L, 10L);
                }
                reopened.remove(1L, 10L);
                reopened.remove(1L, 10L);
                assertThat(reopened.isSaved(1L, 10L)).isFalse();
                assertThat(reopened.isSaved(2L, 10L)).isTrue();
                reopened.saveIfAbsent(1L, 10L, newer);
                reopened.clear(1L);
                assertThat(reopened.isSaved(1L, 10L)).isFalse();
                try (var rows = reopened.newestFirst(1L)) { assertThat(rows.toList()).isEmpty(); }
                assertThat(reopened.isSaved(2L, 10L)).isTrue();
            } finally {
                // Only the uniquely named keyspace created by this test is removed.
                session.execute(com.datastax.oss.driver.api.core.cql.SimpleStatement.newInstance("DROP KEYSPACE IF EXISTS " + keyspace).setTimeout(java.time.Duration.ofSeconds(60)));
            }
        }
    }
}
