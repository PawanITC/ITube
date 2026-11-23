package ITubeBackend.controller;

import ITubeBackend.dto.playlists.*;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.playlists.PlaylistService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/playlists")
@Tag(name = "Playlists", description = "Private playlists owned by the signed-in user")
public class PlaylistController {
    private final PlaylistService service;

    public PlaylistController(PlaylistService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<PlaylistResponse> create(@Valid @RequestBody PlaylistRequest request) {
        var playlist = service.create(CurrentUser.require(), request.title());
        return ResponseEntity.created(URI.create("/api/playlists/" + playlist.id())).body(playlist);
    }

    @GetMapping
    public List<PlaylistResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(CurrentUser.require(), page, size);
    }

    @GetMapping("/{id}")
    public PlaylistDetailResponse detail(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.detail(CurrentUser.require(), id, page, size);
    }

    @PatchMapping("/{id}")
    public PlaylistResponse rename(@PathVariable UUID id, @Valid @RequestBody PlaylistRequest request) {
        return service.rename(CurrentUser.require(), id, request.title());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { service.delete(CurrentUser.require(), id); }

    @PutMapping("/{id}/videos/{videoId}")
    public PlaylistResponse addVideo(@PathVariable UUID id, @PathVariable @Positive Long videoId) {
        return service.addVideo(CurrentUser.require(), id, videoId);
    }

    @DeleteMapping("/{id}/videos/{videoId}")
    public PlaylistResponse removeVideo(@PathVariable UUID id, @PathVariable @Positive Long videoId) {
        return service.removeVideo(CurrentUser.require(), id, videoId);
    }

    @PutMapping("/{id}/videos/{videoId}/position")
    public PlaylistResponse moveVideo(@PathVariable UUID id, @PathVariable @Positive Long videoId,
                                     @Valid @RequestBody PlaylistPositionRequest request) {
        return service.moveVideo(CurrentUser.require(), id, videoId, request.position(), request.version());
    }
}
