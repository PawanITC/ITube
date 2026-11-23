package ITubeBackend.internal;

import ITubeBackend.dto.video.RecommendationCandidate;
import ITubeBackend.dto.video.RecommendationCandidateRequest;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.service.video.RecommendationCatalogService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Covered by the existing /internal/* token guard and never exposed through the gateway. */
@Hidden
@RestController
@RequestMapping("/internal/recommendations")
public class RecommendationCatalogController {
    private final RecommendationCatalogService service;

    public RecommendationCatalogController(RecommendationCatalogService service) { this.service = service; }

    @PostMapping("/candidates")
    public List<RecommendationCandidate> candidates(@Valid @RequestBody RecommendationCandidateRequest request) {
        return service.candidates(request);
    }

    @PostMapping("/videos")
    public List<VideoSummaryDto> eligible(@RequestBody @NotNull @Size(max = 500) List<@NotNull @Positive Long> ids) {
        return service.eligibleVideos(ids);
    }
}
