package ITubeBackend.controller;

import ITubeBackend.dto.entitlement.EntitlementRequest;
import ITubeBackend.dto.entitlement.PlaybackDecision;
import ITubeBackend.service.entitlement.EntitlementService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API (never routed by the gateway; guarded by the internal token).
 * catalog-service asks this before issuing a playback token for a members-only or private video.
 */
@Hidden
@RestController
@RequestMapping("/internal/entitlements")
public class InternalEntitlementController {
    private final EntitlementService entitlementService;

    public InternalEntitlementController(EntitlementService entitlementService) {
        this.entitlementService = entitlementService;
    }

    @PostMapping("/decide")
    public PlaybackDecision decide(@RequestBody EntitlementRequest request) {
        return entitlementService.decide(request.userId(), request.video());
    }
}
