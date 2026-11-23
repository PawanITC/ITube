package ITubeBackend.controller;

import ITubeBackend.dto.playlists.AutomaticPlaylistResponse;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.playlists.AutomaticPlaylistService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/playlists/automatic")
@Tag(name = "Automatic playlists", description = "Personalized mixes generated from the signed-in user's engagement")
public class AutomaticPlaylistController {
    private final AutomaticPlaylistService service;

    public AutomaticPlaylistController(AutomaticPlaylistService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<AutomaticPlaylistResponse> current() {
        return response(service.current(CurrentUser.require(), false));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AutomaticPlaylistResponse> refresh() {
        return response(service.current(CurrentUser.require(), true));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AutomaticPlaylistResponse> snapshot(@PathVariable UUID id) {
        return response(service.snapshot(CurrentUser.require(), id));
    }

    private ResponseEntity<AutomaticPlaylistResponse> response(AutomaticPlaylistResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
