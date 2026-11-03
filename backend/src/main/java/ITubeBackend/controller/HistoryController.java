package ITubeBackend.controller;

import ITubeBackend.dto.history.HistoryResponse;
import ITubeBackend.service.history.HistoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import ITubeBackend.dto.history.HistoryRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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

@Tag(name = "History", description = "Watch history (temporary development userId parameter)")
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @DeleteMapping
    public ResponseEntity<Void> clearHistory(
            @RequestParam("userId") @NotBlank @Size(max = 255) String userId) {
        historyService.clearHistory(userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteHistory(
            @PathVariable("id") @Positive Long id,
            @RequestParam("userId") @NotBlank @Size(max = 255) String userId) {
        boolean deleted = historyService.deleteHistory(id, userId);

        if (deleted) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.notFound().build();
    }

    @GetMapping
    public List<HistoryResponse> getHistory(
            @RequestParam("userId") @NotBlank @Size(max = 255) String userId,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return historyService.getHistory(userId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HistoryResponse addHistory(
            @RequestParam("userId") @NotBlank @Size(max = 255) String userId,
            @Valid @RequestBody HistoryRequest request) {
        return historyService.addHistory(userId, request.videoId());
    }
}
