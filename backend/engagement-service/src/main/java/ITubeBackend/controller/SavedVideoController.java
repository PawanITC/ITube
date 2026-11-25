package ITubeBackend.controller;

import ITubeBackend.dto.savedvideos.SavedVideoResponse;
import ITubeBackend.dto.savedvideos.SavedVideoStatusResponse;
import ITubeBackend.dto.engagement.OperationResponse;
import ITubeBackend.engagement.commands.CommandGateway;
import static ITubeBackend.engagement.commands.EngagementCommand.Action.*;
import org.springframework.http.ResponseEntity;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.savedvideos.SavedVideoService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "Saved videos", description = "Manage the signed-in user's Watch later list")
@RestController
@RequestMapping("/api/saved-videos")
public class SavedVideoController {
    private final SavedVideoService service;
    private final CommandGateway commands;

    public SavedVideoController(SavedVideoService service, CommandGateway commands) { this.service = service; this.commands = commands; }

    @PutMapping("/{videoId}")
    public ResponseEntity<OperationResponse> save(@PathVariable @Positive Long videoId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, SAVE, videoId, null, null, null, null);
    }

    @DeleteMapping("/{videoId}")
    public ResponseEntity<OperationResponse> remove(@PathVariable @Positive Long videoId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, UNSAVE, videoId, null, null, null, null);
    }

    @DeleteMapping
    public ResponseEntity<OperationResponse> clear(@RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, SAVED_CLEAR, null, null, null, null, null);
    }

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
