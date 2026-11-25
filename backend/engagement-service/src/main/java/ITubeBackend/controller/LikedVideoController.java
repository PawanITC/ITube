package ITubeBackend.controller;

import ITubeBackend.dto.likedvideos.LikedVideoResponse;
import ITubeBackend.dto.likedvideos.LikedVideoStatusResponse;
import ITubeBackend.dto.engagement.OperationResponse;
import ITubeBackend.engagement.commands.CommandGateway;
import static ITubeBackend.engagement.commands.EngagementCommand.Action.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.likedvideos.LikedVideoService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Liked videos", description = "Manage the signed-in user's liked videos")
@RestController
@RequestMapping("/api/liked-videos")
public class LikedVideoController {

    private final LikedVideoService likedVideoService;
    private final CommandGateway commands;

    public LikedVideoController(LikedVideoService likedVideoService, CommandGateway commands) {
        this.likedVideoService = likedVideoService;
        this.commands = commands;
    }

    @PutMapping("/{videoId}")
    public ResponseEntity<OperationResponse> likeVideo(@PathVariable("videoId") @Positive Long videoId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, LIKE, videoId, null, null, null, null);
    }

    @DeleteMapping("/{videoId}")
    public ResponseEntity<OperationResponse> unlikeVideo(@PathVariable("videoId") @Positive Long videoId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, UNLIKE, videoId, null, null, null, null);
    }

    @GetMapping("/{videoId}")
    public LikedVideoStatusResponse getLikeStatus(@PathVariable("videoId") @Positive Long videoId) {
        return likedVideoService.getLikeStatus(CurrentUser.require(), videoId);
    }

    @GetMapping
    public List<LikedVideoResponse> getLikedVideos(
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        Long userId = CurrentUser.require();
        return likedVideoService.getLikedVideos(userId, page, size);
    }
}
