package ITubeBackend.controller;

import ITubeBackend.dto.engagement.OperationResponse;
import ITubeBackend.engagement.commands.CommandGateway;
import ITubeBackend.security.CurrentUser;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/engagement/operations")
public class EngagementOperationController {
    private final CommandGateway commands;
    public EngagementOperationController(CommandGateway commands) { this.commands = commands; }
    @GetMapping("/{id}")
    public ResponseEntity<OperationResponse> status(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(commands.status(CurrentUser.require(), id));
    }
}
