package ITubeBackend.controller;

import ITubeBackend.dto.savedvideos.SavedVideoResponse;
import ITubeBackend.dto.savedvideos.SavedVideoStatusResponse;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.savedvideos.SavedVideoService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "Saved videos", description = "Manage the signed-in user's Watch later list")
@RestController
@RequestMapping("/api/saved-videos")
public class SavedVideoController {
    private final SavedVideoService service;

    public SavedVideoController(SavedVideoService service) { this.service = service; }

    @PutMapping("/{videoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable @Positive Long videoId) {
        service.save(CurrentUser.require(), videoId);
    }

    @DeleteMapping("/{videoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable @Positive Long videoId) {
        service.remove(CurrentUser.require(), videoId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() { service.clear(CurrentUser.require()); }

    @GetMapping("/{videoId}")
    public SavedVideoStatusResponse status(@PathVariable @Positive Long videoId) {
        return service.status(CurrentUser.require(), videoId);
    }

    @GetMapping
    public List<SavedVideoResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(CurrentUser.require(), page, size);
    }
}
