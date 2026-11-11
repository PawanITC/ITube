package ITubeBackend.service.likedvideos;

import ITubeBackend.dto.likedvideos.LikedVideoResponse;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.dto.likedvideos.LikedVideoRow;
import ITubeBackend.dto.likedvideos.LikedVideoStatusResponse;
import ITubeBackend.repository.LikedVideoRepository;
import ITubeBackend.repository.AccountRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.entities.VideoVisibility;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.time.Clock;

@Service
public class LikedVideoService {

    private final LikedVideoRepository likedVideoRepository;
    private final AccountRepository accountRepository;
    private final VideoRepository videoRepository;
    private final Clock clock;

    public LikedVideoService(LikedVideoRepository likedVideoRepository,
                             AccountRepository accountRepository,
                             VideoRepository videoRepository, Clock clock) {
        this.likedVideoRepository = likedVideoRepository;
        this.accountRepository = accountRepository;
        this.videoRepository = videoRepository;
        this.clock = clock;
    }

    @Transactional
    public void likeVideo(Long userId, Long videoId) {
        requireAccount(userId);
        requireVisibleVideo(userId, videoId);
        likedVideoRepository.insertIfAbsent(userId, videoId, clock.instant());
    }

    @Transactional
    public void unlikeVideo(Long userId, Long videoId) {
        requireAccount(userId);
        validateVideoId(videoId);
        // Still allow removal if the video was deleted or has since become private.
        likedVideoRepository.deleteForUserAndVideo(userId, videoId);
    }

    @Transactional(readOnly = true)
    public LikedVideoStatusResponse getLikeStatus(Long userId, Long videoId) {
        requireAccount(userId);
        requireVisibleVideo(userId, videoId);
        boolean liked = likedVideoRepository.existsByUser_UserIdAndVideo_VideoId(userId, videoId);
        return new LikedVideoStatusResponse(videoId, liked);
    }

    private void validateVideoId(Long videoId) {
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

    @Transactional(readOnly = true)
    public List<LikedVideoResponse> getLikedVideos(Long userId, int page, int size) {
        requireAccount(userId);
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be nonnegative and size must be 1 to 100");
        }
        List<LikedVideoRow> likes = likedVideoRepository
                .findVisibleLikesByUserId(userId, PageRequest.of(page, size));

        List<LikedVideoResponse> responses = new ArrayList<>();
        for (LikedVideoRow like : likes) {
            VideoSummaryDto summary = new VideoSummaryDto(
                    like.videoId(), like.channelId(), like.title(), like.channelName(),
                    like.duration(), like.hasThumbnail(), like.processingStatus(),
                    like.visibility(), like.requiredTierLevel()
            );
            responses.add(new LikedVideoResponse(summary, like.likedAt()));
        }
        return responses;
    }
}
