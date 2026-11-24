package ITubeBackend.controller;

import ITubeBackend.dto.playlists.*;
import ITubeBackend.dto.engagement.OperationResponse;
import ITubeBackend.engagement.commands.CommandGateway;
import static ITubeBackend.engagement.commands.EngagementCommand.Action.*;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.playlists.PlaylistService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/playlists")
@Tag(name = "Playlists", description = "Private playlists owned by the signed-in user")
public class PlaylistController {
    private final PlaylistService service;
    private final CommandGateway commands;

    public PlaylistController(PlaylistService service, CommandGateway commands) { this.service = service; this.commands = commands; }

    @PostMapping
    public ResponseEntity<OperationResponse> create(@Valid @RequestBody PlaylistRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, PLAYLIST_CREATE, null, null, request.title(), null, null);
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
    public ResponseEntity<OperationResponse> rename(@PathVariable UUID id, @Valid @RequestBody PlaylistRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, PLAYLIST_RENAME, null, id, request.title(), null, null);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<OperationResponse> delete(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, PLAYLIST_DELETE, null, id, null, null, null);
    }

    @PutMapping("/{id}/videos/{videoId}")
    public ResponseEntity<OperationResponse> addVideo(@PathVariable UUID id, @PathVariable @Positive Long videoId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, PLAYLIST_ADD, videoId, id, null, null, null);
    }

    @DeleteMapping("/{id}/videos/{videoId}")
    public ResponseEntity<OperationResponse> removeVideo(@PathVariable UUID id, @PathVariable @Positive Long videoId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, PLAYLIST_REMOVE, videoId, id, null, null, null);
    }

    @PutMapping("/{id}/videos/{videoId}/position")
    public ResponseEntity<OperationResponse> moveVideo(@PathVariable UUID id, @PathVariable @Positive Long videoId,
            @Valid @RequestBody PlaylistPositionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, PLAYLIST_MOVE, videoId, id, null, request.position(), request.version());
    }
}
