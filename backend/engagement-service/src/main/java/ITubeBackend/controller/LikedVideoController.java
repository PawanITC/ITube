package ITubeBackend.controller;

import ITubeBackend.dto.likedvideos.LikedVideoResponse;
import ITubeBackend.dto.likedvideos.LikedVideoStatusResponse;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.likedvideos.LikedVideoService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    public LikedVideoController(LikedVideoService likedVideoService) {
        this.likedVideoService = likedVideoService;
    }

    @PutMapping("/{videoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void likeVideo(HttpSession session, @PathVariable("videoId") @Positive Long videoId) {
        likedVideoService.likeVideo(CurrentUser.require(session), videoId);
    }

    @DeleteMapping("/{videoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlikeVideo(HttpSession session, @PathVariable("videoId") @Positive Long videoId) {
        likedVideoService.unlikeVideo(CurrentUser.require(session), videoId);
    }

    @GetMapping("/{videoId}")
    public LikedVideoStatusResponse getLikeStatus(HttpSession session,
                                                 @PathVariable("videoId") @Positive Long videoId) {
        return likedVideoService.getLikeStatus(CurrentUser.require(session), videoId);
    }

    @GetMapping
    public List<LikedVideoResponse> getLikedVideos(
            HttpSession session,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        Long userId = CurrentUser.require(session);
        return likedVideoService.getLikedVideos(userId, page, size);
    }
}
