package ITubeBackend.controller;

import ITubeBackend.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import ITubeBackend.dto.comments.CommentDto;
import ITubeBackend.dto.comments.CreateCommentRequest;
import ITubeBackend.service.comment.CommentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Comments", description = "Video comments")
@RestController
@RequestMapping("/api/videos/{videoId}/comments")
public class CommentController {
    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    public List<CommentDto> getComments(@PathVariable Long videoId,
                                        @RequestParam(value = "limit", defaultValue = "100") int limit) {
        // Newest first, capped (default 100, max 500) so a popular video never returns its whole history
        return commentService.getComments(videoId, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentDto addComment(@PathVariable Long videoId, @RequestBody CreateCommentRequest request) {
        Long userId = CurrentUser.require("Sign in before commenting");
        return commentService.addComment(videoId, userId, CurrentUser.displayName().orElse(null), request);
    }
}
