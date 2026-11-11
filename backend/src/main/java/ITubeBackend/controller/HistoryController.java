package ITubeBackend.controller;

import ITubeBackend.dto.history.HistoryResponse;
import ITubeBackend.security.CurrentUser;
import jakarta.servlet.http.HttpSession;
import ITubeBackend.service.history.HistoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import ITubeBackend.dto.history.HistoryRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Reuse the team session identity; keep the existing service and VARCHAR user_id schema.
@Tag(name = "History", description = "Watch history for the signed-in user")
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @DeleteMapping
    public ResponseEntity<Void> clearHistory(
            HttpSession session) {
        historyService.clearHistory(CurrentUser.require(session).toString());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteHistory(
            @PathVariable("id") @Positive Long id,
            HttpSession session) {
        boolean deleted = historyService.deleteHistory(id, CurrentUser.require(session).toString());

        if (deleted) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.notFound().build();
    }

    @GetMapping
    public List<HistoryResponse> getHistory(
            HttpSession session,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return historyService.getHistory(CurrentUser.require(session).toString(), page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HistoryResponse addHistory(
            HttpSession session,
            @Valid @RequestBody HistoryRequest request) {
        return historyService.addHistory(CurrentUser.require(session).toString(), request.videoId());
    }
}
