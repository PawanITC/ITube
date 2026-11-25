package ITubeBackend.controller;

import ITubeBackend.dto.history.HistoryResponse;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.history.HistoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import ITubeBackend.dto.history.HistoryRequest;
import ITubeBackend.dto.engagement.OperationResponse;
import ITubeBackend.engagement.commands.CommandGateway;
import static ITubeBackend.engagement.commands.EngagementCommand.Action.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Identity comes from the access token. Entry ids are UUIDs; they are parsed after the sign-in check so a
// request without a token gets 401 whatever the id looks like.
@Tag(name = "History", description = "Watch history for the signed-in user")
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;
    private final CommandGateway commands;

    public HistoryController(HistoryService historyService, CommandGateway commands) {
        this.historyService = historyService;
        this.commands = commands;
    }

    @DeleteMapping
    public ResponseEntity<OperationResponse> clearHistory(@RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, HISTORY_CLEAR, null, null, null, null, null);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<OperationResponse> deleteHistory(@PathVariable("id") String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long user = CurrentUser.require();
        java.util.UUID parsed;
        try { parsed = java.util.UUID.fromString(id); }
        catch (IllegalArgumentException exception) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid history entry id");
        }
        return commands.submit(user, key, HISTORY_REMOVE, null, parsed, null, null, null);
    }

    @GetMapping
    public List<HistoryResponse> getHistory(
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return historyService.getHistory(CurrentUser.require(), page, size);
    }

    @PostMapping
    public ResponseEntity<OperationResponse> addHistory(@Valid @RequestBody HistoryRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return commands.submit(CurrentUser.require(), key, HISTORY_ADD, request.videoId(), null, null, null, null);
    }
}
