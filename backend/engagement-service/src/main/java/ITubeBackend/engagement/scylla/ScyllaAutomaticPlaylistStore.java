package ITubeBackend.engagement.scylla;

import ITubeBackend.engagement.AutomaticPlaylistStore;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Each snapshot is a small partition; TTL bounds storage even as recommendations are refreshed. */
public class ScyllaAutomaticPlaylistStore implements AutomaticPlaylistStore {
    private final CqlSession session;
    private final PreparedStatement insert, find;

    public ScyllaAutomaticPlaylistStore(CqlSession session, String keyspace) {
        this.session = session;
        insert = session.prepare("INSERT INTO " + keyspace + ".automatic_playlist_snapshots (user_id, snapshot_id, generated_at, personalized, video_ids) VALUES (?, ?, ?, ?, ?) USING TTL " + TTL_SECONDS);
        find = session.prepare("SELECT generated_at, personalized, video_ids FROM " + keyspace + ".automatic_playlist_snapshots WHERE user_id = ? AND snapshot_id = ?");
    }

    @Override
    public void save(Snapshot value) {
        session.execute(insert.bind(value.userId(), value.id(), value.generatedAt(), value.personalized(), value.videoIds()));
    }

    @Override
    public Optional<Snapshot> find(long userId, UUID id) {
        var row = session.execute(find.bind(userId, id)).one();
        if (row == null || row.isNull("generated_at")) return Optional.empty();
        var ids = row.getList("video_ids", Long.class);
        return Optional.of(new Snapshot(id, userId, row.getInstant("generated_at"), row.getBoolean("personalized"),
                ids == null ? List.of() : ids));
    }
}
