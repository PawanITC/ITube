package ITubeBackend.engagement;

import java.time.Instant;
import java.util.stream.Stream;

/** Liked videos, per user newest first, plus a per-video index used for cleanup when a video is deleted. */
public interface LikeStore {
    /** Returns false (and keeps the original time) if the user already likes the video. */
    boolean likeIfAbsent(long userId, long videoId, Instant likedAt);

    void unlike(long userId, long videoId);

    boolean isLiked(long userId, long videoId);

    /** Lazily paged, newest first (ties: higher video id first). Close the stream when done. */
    Stream<Like> newestFirst(long userId);

    void deleteAllForVideo(long videoId);

    record Like(long userId, long videoId, Instant likedAt) {
    }
}
