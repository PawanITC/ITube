package ITubeBackend.service.comment;

import ITubeBackend.dto.accounts.AccountDto;
import ITubeBackend.dto.comments.CommentDto;
import ITubeBackend.dto.comments.CreateCommentRequest;
import ITubeBackend.entities.AccountEntity;
import ITubeBackend.entities.CommentEntity;
import ITubeBackend.entities.VideoEntity;
import ITubeBackend.repository.AccountRepository;
import ITubeBackend.repository.CommentRepository;
import ITubeBackend.repository.VideoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class CommentService {
    private static final int MAX_COMMENT_LENGTH = 5000;

    private final CommentRepository commentRepository;
    private final VideoRepository videoRepository;
    private final AccountRepository accountRepository;

    public CommentService(CommentRepository commentRepository, VideoRepository videoRepository,
                          AccountRepository accountRepository) {
        this.commentRepository = commentRepository;
        this.videoRepository = videoRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<CommentDto> getComments(Long videoId) {
        if (!videoRepository.existsById(videoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        return commentRepository.findDtosByVideoId(videoId);
    }

    @Transactional
    public CommentDto addComment(Long videoId, Long userId, CreateCommentRequest request) {
        String content = request == null || request.content() == null ? "" : request.content().trim();
        if (content.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment cannot be empty");
        }
        if (content.length() > MAX_COMMENT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment must be 5000 characters or fewer");
        }

        VideoEntity video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
        AccountDto user = accountRepository.findDtoById(userId);

        CommentEntity comment = new CommentEntity();
        comment.setVideo(video);
        comment.setUser(accountRepository.getReferenceById(userId));
        comment.setContent(content);
        comment.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        CommentEntity saved = commentRepository.save(comment);
        String authorName = user.firstname() + " " + user.lastname();
        return new CommentDto(saved.getCommentId(), saved.getContent(), saved.getCreatedAt(), authorName);
    }
}
