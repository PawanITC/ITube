package ITubeBackend.service.comment;

import ITubeBackend.dto.accounts.AccountDto;
import ITubeBackend.dto.comments.CommentDto;
import ITubeBackend.dto.comments.CreateCommentRequest;
import ITubeBackend.engagement.CommentStore;
import ITubeBackend.repository.AccountRepository;
import ITubeBackend.repository.VideoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

/** Comments live in ScyllaDB, one partition per video; the author's name is copied in when posted. */
@Service
public class CommentService {
    private static final int MAX_COMMENT_LENGTH = 5000;
    public static final int MAX_PAGE = 500;

    private final CommentStore commentStore;
    private final VideoRepository videoRepository;
    private final AccountRepository accountRepository;
    private final Clock clock;

    public CommentService(CommentStore commentStore, VideoRepository videoRepository,
                          AccountRepository accountRepository, Clock clock) {
        this.commentStore = commentStore;
        this.videoRepository = videoRepository;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    public List<CommentDto> getComments(Long videoId, int limit) {
        if (!videoRepository.existsById(videoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        return commentStore.newestFirst(videoId, Math.clamp(limit, 1, MAX_PAGE)).stream()
                .map(comment -> new CommentDto(comment.id(), comment.content(),
                        comment.createdAt().atOffset(ZoneOffset.UTC), comment.authorName()))
                .toList();
    }

    public CommentDto addComment(Long videoId, Long userId, CreateCommentRequest request) {
        String content = request == null || request.content() == null ? "" : request.content().trim();
        if (content.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment cannot be empty");
        }
        if (content.length() > MAX_COMMENT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment must be 5000 characters or fewer");
        }
        if (!videoRepository.existsById(videoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        AccountDto user = accountRepository.findDtoById(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Start a session before commenting");
        }
        String authorName = user.firstname() + " " + user.lastname();
        var saved = commentStore.add(videoId, userId, authorName, content, clock.instant());
        return new CommentDto(saved.id(), saved.content(), saved.createdAt().atOffset(ZoneOffset.UTC), authorName);
    }
}
