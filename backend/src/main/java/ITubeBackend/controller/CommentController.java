package ITubeBackend.controller;

import ITubeBackend.dto.comments.CommentDto;
import ITubeBackend.dto.comments.CreateCommentRequest;
import ITubeBackend.service.comment.CommentService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/videos/{videoId}/comments")
public class CommentController {
    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    public List<CommentDto> getComments(@PathVariable Long videoId) {
        return commentService.getComments(videoId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentDto addComment(@PathVariable Long videoId, @RequestBody CreateCommentRequest request,
                                 HttpSession session) {
        Object sessionUserId = session.getAttribute("userId");
        if (!(sessionUserId instanceof Long userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Start a session before commenting");
        }
        return commentService.addComment(videoId, userId, request);
    }
}
