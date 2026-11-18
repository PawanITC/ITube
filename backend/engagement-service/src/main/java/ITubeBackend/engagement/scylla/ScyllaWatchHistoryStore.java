package ITubeBackend.engagement.scylla;

import ITubeBackend.engagement.TimeUuids;
import ITubeBackend.engagement.WatchHistoryStore;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

public class ScyllaWatchHistoryStore implements WatchHistoryStore {
    private final CqlSession session;
    private final PreparedStatement insert;
    private final PreparedStatement selectNewest;
    private final PreparedStatement selectOne;
    private final PreparedStatement deleteOne;
    private final PreparedStatement deleteAll;

    public ScyllaWatchHistoryStore(CqlSession session, String keyspace) {
        this.session = session;
        String table = keyspace + ".watch_history_by_user";
        insert = session.prepare("INSERT INTO " + table + " (user_id, entry_id, video_id, watched_at) VALUES (?, ?, ?, ?)");
        selectNewest = session.prepare("SELECT entry_id, video_id, watched_at FROM " + table + " WHERE user_id = ? LIMIT ?");
        selectOne = session.prepare("SELECT entry_id, video_id, watched_at FROM " + table + " WHERE user_id = ? AND entry_id = ?");
        deleteOne = session.prepare("DELETE FROM " + table + " WHERE user_id = ? AND entry_id = ?");
        deleteAll = session.prepare("DELETE FROM " + table + " WHERE user_id = ?");
    }

    @Override
    public HistoryEntry add(long userId, long videoId, Instant watchedAt) {
        UUID id = TimeUuids.at(watchedAt);
        session.execute(insert.bind(userId, id, videoId, watchedAt));
        return new HistoryEntry(id, userId, videoId, watchedAt);
    }

    @Override
    public void put(HistoryEntry entry) {
        session.execute(insert.bind(entry.userId(), entry.id(), entry.videoId(), entry.watchedAt()));
    }

    @Override
    public List<HistoryEntry> page(long userId, int offset, int limit) {
        int wanted = offset + limit;
        var rows = session.execute(selectNewest.bind(userId, wanted).setPageSize(Math.min(wanted, 500)));
        return StreamSupport.stream(rows.spliterator(), false).skip(offset)
                .map(row -> toEntry(userId, row)).toList();
    }

    @Override
    public Optional<HistoryEntry> find(long userId, UUID entryId) {
        return Optional.ofNullable(session.execute(selectOne.bind(userId, entryId)).one())
                .map(row -> toEntry(userId, row));
    }

    @Override
    public void delete(long userId, UUID entryId) {
        session.execute(deleteOne.bind(userId, entryId));
    }

    @Override
    public void clear(long userId) {
        // A single partition tombstone, however long the history is
        session.execute(deleteAll.bind(userId));
    }

    private static HistoryEntry toEntry(long userId, Row row) {
        return new HistoryEntry(row.getUuid("entry_id"), userId, row.getLong("video_id"), row.getInstant("watched_at"));
    }
}
