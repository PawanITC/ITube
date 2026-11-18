package ITubeBackend.service.comment;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.client.IdentityClient;
import ITubeBackend.dto.comments.CommentDto;
import ITubeBackend.dto.comments.CreateCommentRequest;
import ITubeBackend.engagement.CommentStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Comments live in ScyllaDB, one partition per video; the author's name is copied in when posted.
 * The name comes from the session (put there at sign-in), so posting needs no call to identity-service;
 * identity is only asked for sessions created before names were stored in them.
 */
@Service
public class CommentService {
    private static final int MAX_COMMENT_LENGTH = 5000;
    public static final int MAX_PAGE = 500;

    private final CommentStore commentStore;
    private final CatalogClient catalog;
    private final IdentityClient identity;
    private final Clock clock;

    public CommentService(CommentStore commentStore, CatalogClient catalog, IdentityClient identity, Clock clock) {
        this.commentStore = commentStore;
        this.catalog = catalog;
        this.identity = identity;
        this.clock = clock;
    }

    public List<CommentDto> getComments(Long videoId, int limit) {
        requireVideo(videoId);
        return commentStore.newestFirst(videoId, Math.clamp(limit, 1, MAX_PAGE)).stream()
                .map(comment -> new CommentDto(comment.id(), comment.content(),
                        comment.createdAt().atOffset(ZoneOffset.UTC), comment.authorName()))
                .toList();
    }

    /** {@code sessionDisplayName} may be null for sessions created before names were stored in them. */
    public CommentDto addComment(Long videoId, Long userId, String sessionDisplayName, CreateCommentRequest request) {
        String content = request == null || request.content() == null ? "" : request.content().trim();
        if (content.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment cannot be empty");
        }
        if (content.length() > MAX_COMMENT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment must be 5000 characters or fewer");
        }
        requireVideo(videoId);
        String authorName = sessionDisplayName != null ? sessionDisplayName
                : identity.account(userId).map(user -> user.firstname() + " " + user.lastname())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Start a session before commenting"));
        var saved = commentStore.add(videoId, userId, authorName, content, clock.instant());
        return new CommentDto(saved.id(), saved.content(), saved.createdAt().atOffset(ZoneOffset.UTC), authorName);
    }

    private void requireVideo(Long videoId) {
        if (catalog.videoAccess(videoId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
    }
}
