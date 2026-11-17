package ITubeBackend.engagement.memory;

import ITubeBackend.engagement.CommentStore;
import ITubeBackend.engagement.LikeStore;
import ITubeBackend.engagement.TimeUuids;
import ITubeBackend.engagement.WatchHistoryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Single-process stand-ins for the ScyllaDB stores, with the same ordering rules. Used by tests and the
 * h2 profile (itube.engagement.store=memory). Data is lost on restart.
 */
public final class InMemoryEngagementStores {
    private InMemoryEngagementStores() {
    }

    /** Implemented by every in-memory store so tests can start from empty. */
    public interface Resettable {
        void reset();
    }

    public static class History implements WatchHistoryStore, Resettable {
        private record Row(WatchHistoryStore.HistoryEntry entry, long seq) {
        }

        // Newest first; ties on time go to the later insert, like a timeuuid clustering key
        private static final Comparator<Row> NEWEST_FIRST = Comparator
                .comparing((Row row) -> row.entry().watchedAt()).thenComparingLong(Row::seq).reversed();

        private final Map<Long, List<Row>> byUser = new HashMap<>();
        private long seq;

        @Override
        public synchronized HistoryEntry add(long userId, long videoId, Instant watchedAt) {
            HistoryEntry entry = new HistoryEntry(TimeUuids.at(watchedAt), userId, videoId, watchedAt);
            byUser.computeIfAbsent(userId, id -> new ArrayList<>()).add(new Row(entry, ++seq));
            return entry;
        }

        @Override
        public synchronized void put(HistoryEntry entry) {
            delete(entry.userId(), entry.id());
            byUser.computeIfAbsent(entry.userId(), id -> new ArrayList<>()).add(new Row(entry, ++seq));
        }

        @Override
        public synchronized List<HistoryEntry> page(long userId, int offset, int limit) {
            return byUser.getOrDefault(userId, List.of()).stream().sorted(NEWEST_FIRST)
                    .skip(offset).limit(limit).map(Row::entry).toList();
        }

        @Override
        public synchronized Optional<HistoryEntry> find(long userId, UUID entryId) {
            return byUser.getOrDefault(userId, List.of()).stream().map(Row::entry)
                    .filter(entry -> entry.id().equals(entryId)).findFirst();
        }

        @Override
        public synchronized void delete(long userId, UUID entryId) {
            List<Row> rows = byUser.get(userId);
            if (rows != null) rows.removeIf(row -> row.entry().id().equals(entryId));
        }

        @Override
        public synchronized void clear(long userId) {
            byUser.remove(userId);
        }

        @Override
        public synchronized void reset() {
            byUser.clear();
        }
    }

    public static class Likes implements LikeStore, Resettable {
        private static final Comparator<Like> NEWEST_FIRST = Comparator.comparing(Like::likedAt)
                .thenComparingLong(Like::videoId).reversed();

        // userId -> videoId -> like
        private final Map<Long, Map<Long, Like>> byUser = new HashMap<>();

        @Override
        public synchronized boolean likeIfAbsent(long userId, long videoId, Instant likedAt) {
            return byUser.computeIfAbsent(userId, id -> new HashMap<>())
                    .putIfAbsent(videoId, new Like(userId, videoId, likedAt)) == null;
        }

        @Override
        public synchronized void unlike(long userId, long videoId) {
            Map<Long, Like> likes = byUser.get(userId);
            if (likes != null) likes.remove(videoId);
        }

        @Override
        public synchronized boolean isLiked(long userId, long videoId) {
            return byUser.getOrDefault(userId, Map.of()).containsKey(videoId);
        }

        @Override
        public synchronized Stream<Like> newestFirst(long userId) {
            return byUser.getOrDefault(userId, Map.of()).values().stream().sorted(NEWEST_FIRST).toList().stream();
        }

        @Override
        public synchronized void deleteAllForVideo(long videoId) {
            byUser.values().forEach(likes -> likes.remove(videoId));
        }

        @Override
        public synchronized void reset() {
            byUser.clear();
        }
    }

    public static class Comments implements CommentStore, Resettable {
        private final Map<Long, List<Comment>> byVideo = new HashMap<>();

        @Override
        public synchronized Comment add(long videoId, long userId, String authorName, String content, Instant createdAt) {
            Comment comment = new Comment(TimeUuids.at(createdAt), videoId, userId, authorName, content, createdAt);
            byVideo.computeIfAbsent(videoId, id -> new ArrayList<>()).add(comment);
            return comment;
        }

        @Override
        public synchronized void put(Comment comment) {
            List<Comment> comments = byVideo.computeIfAbsent(comment.videoId(), id -> new ArrayList<>());
            comments.removeIf(existing -> existing.id().equals(comment.id()));
            comments.add(comment);
        }

        @Override
        public synchronized List<Comment> newestFirst(long videoId, int limit) {
            List<Comment> comments = new ArrayList<>(byVideo.getOrDefault(videoId, List.of()));
            java.util.Collections.reverse(comments);
            return comments.stream().sorted(Comparator.comparing(Comment::createdAt).reversed()).limit(limit).toList();
        }

        @Override
        public synchronized void deleteAllForVideo(long videoId) {
            byVideo.remove(videoId);
        }

        @Override
        public synchronized void reset() {
            byVideo.clear();
        }
    }
}
