package ITubeBackend.engagement.scylla;

import ITubeBackend.engagement.PlaylistStore;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** One bounded playlist row: metadata and order change together in a lightweight transaction. */
public class ScyllaPlaylistStore implements PlaylistStore {
    private final CqlSession session;
    private final PreparedStatement insert, find, list, replace, delete;

    public ScyllaPlaylistStore(CqlSession session, String keyspace) {
        this.session = session;
        String table = keyspace + ".playlists_by_user";
        insert = session.prepare("INSERT INTO " + table + " (user_id, playlist_id, title, created_at, updated_at, version, video_ids, added_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) IF NOT EXISTS");
        find = session.prepare("SELECT * FROM " + table + " WHERE user_id = ? AND playlist_id = ?");
        list = session.prepare("SELECT * FROM " + table + " WHERE user_id = ?");
        replace = session.prepare("UPDATE " + table + " SET title = ?, updated_at = ?, version = ?, video_ids = ?, added_at = ? WHERE user_id = ? AND playlist_id = ? IF version = ?");
        delete = session.prepare("DELETE FROM " + table + " WHERE user_id = ? AND playlist_id = ? IF version = ?");
    }

    @Override
    public boolean create(Playlist playlist) {
        return session.execute(insert.bind(playlist.userId(), playlist.id(), playlist.title(), playlist.createdAt(),
                playlist.updatedAt(), playlist.version(), videoIds(playlist), addedAt(playlist))).wasApplied();
    }

    @Override
    public Optional<Playlist> find(long userId, UUID playlistId) {
        // Serial reads see completed conditional writes before a subsequent edit is attempted.
        Row row = session.execute(find.bind(userId, playlistId)
                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL)).one();
        return row == null ? Optional.empty() : Optional.of(read(row));
    }

    @Override
    public Stream<Playlist> newestFirst(long userId) {
        var rows = session.execute(list.bind(userId).setPageSize(20));
        return StreamSupport.stream(rows.spliterator(), false).map(this::read);
    }

    @Override
    public boolean replace(Playlist before, Playlist after) {
        return session.execute(replace.bind(after.title(), after.updatedAt(), after.version(), videoIds(after),
                addedAt(after), before.userId(), before.id(), before.version())).wasApplied();
    }

    @Override
    public boolean delete(Playlist playlist) {
        return session.execute(delete.bind(playlist.userId(), playlist.id(), playlist.version())).wasApplied();
    }

    private java.util.List<Long> videoIds(Playlist playlist) {
        return playlist.items().stream().map(Item::videoId).toList();
    }

    private Map<Long, Instant> addedAt(Playlist playlist) {
        Map<Long, Instant> result = new LinkedHashMap<>();
        playlist.items().forEach(item -> result.put(item.videoId(), item.addedAt()));
        return result;
    }

    private Playlist read(Row row) {
        var times = row.getMap("added_at", Long.class, Instant.class);
        var ids = row.getList("video_ids", Long.class);
        // CQL represents empty collections as null on some driver/server combinations.
        var items = ids == null ? java.util.List.<Item>of()
                : ids.stream().map(id -> new Item(id, times.get(id))).toList();
        return new Playlist(row.getUuid("playlist_id"), row.getLong("user_id"), row.getString("title"),
                row.getInstant("created_at"), row.getInstant("updated_at"), row.getUuid("version"), items);
    }
}
