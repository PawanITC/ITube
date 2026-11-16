package ITubeBackend.service.likedvideos;

import ITubeBackend.dto.likedvideos.LikedVideoResponse;
import ITubeBackend.dto.likedvideos.LikedVideoStatusResponse;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.engagement.LikeStore;
import ITubeBackend.engagement.LikeStore.Like;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.AccountRepository;
import ITubeBackend.repository.VideoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Likes live in ScyllaDB (engagement); video details and visibility come from the catalog by id.
 * Likes of videos the viewer can no longer see (deleted, or private and not theirs) are skipped.
 */
@Service
public class LikedVideoService {
    // How many likes to resolve against the catalog per query while filling a page
    private static final int LOOKUP_BATCH = 50;

    private final LikeStore likeStore;
    private final AccountRepository accountRepository;
    private final VideoRepository videoRepository;
    private final Clock clock;

    public LikedVideoService(LikeStore likeStore, AccountRepository accountRepository,
                             VideoRepository videoRepository, Clock clock) {
        this.likeStore = likeStore;
        this.accountRepository = accountRepository;
        this.videoRepository = videoRepository;
        this.clock = clock;
    }

    public void likeVideo(Long userId, Long videoId) {
        requireAccount(userId);
        requireVisibleVideo(userId, videoId);
        likeStore.likeIfAbsent(userId, videoId, clock.instant());
    }

    public void unlikeVideo(Long userId, Long videoId) {
        requireAccount(userId);
        validateVideoId(videoId);
        // Still allow removal if the video was deleted or has since become private.
        likeStore.unlike(userId, videoId);
    }

    public LikedVideoStatusResponse getLikeStatus(Long userId, Long videoId) {
        requireAccount(userId);
        requireVisibleVideo(userId, videoId);
        return new LikedVideoStatusResponse(videoId, likeStore.isLiked(userId, videoId));
    }

    public List<LikedVideoResponse> getLikedVideos(Long userId, int page, int size) {
        requireAccount(userId);
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be nonnegative and size must be 1 to 100");
        }
        int toSkip = page * size;
        List<LikedVideoResponse> responses = new ArrayList<>(size);
        try (Stream<Like> likes = likeStore.newestFirst(userId)) {
            Iterator<Like> iterator = likes.iterator();
            while (iterator.hasNext() && responses.size() < size) {
                List<Like> batch = new ArrayList<>(LOOKUP_BATCH);
                while (iterator.hasNext() && batch.size() < LOOKUP_BATCH) batch.add(iterator.next());
                Map<Long, VideoSummaryDto> visible = videoRepository.findVisibleVideoSummaries(
                                batch.stream().map(Like::videoId).toList(), userId).stream()
                        .collect(Collectors.toMap(VideoSummaryDto::videoId, Function.identity()));
                for (Like like : batch) {
                    VideoSummaryDto video = visible.get(like.videoId());
                    if (video == null) continue;
                    if (toSkip > 0) {
                        toSkip--;
                        continue;
                    }
                    responses.add(new LikedVideoResponse(video, like.likedAt()));
                    if (responses.size() == size) break;
                }
            }
        }
        return responses;
    }

    private static void validateVideoId(Long videoId) {
        if (videoId == null || videoId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A positive video ID is required");
        }
    }

    private void requireAccount(Long userId) {
        if (userId == null || !accountRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Start a session first");
        }
    }

    private void requireVisibleVideo(Long userId, Long videoId) {
        validateVideoId(videoId);
        var video = videoRepository.findAccess(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
        if (video.visibility() == VideoVisibility.PRIVATE && !userId.equals(video.ownerUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
    }
}
