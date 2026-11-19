package ITubeBackend.controller;

import ITubeBackend.dto.accounts.DevSessionDto;
import ITubeBackend.entities.AccountEntity;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.account.AccountService;
import ITubeBackend.service.account.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** /api/channels/mine moved to catalog-service (channels are catalog data). */
@Tag(name = "Dev session", description = "Temporary sign-in until login is finished")
@RestController
public class DevSessionController {
    private final AccountService accountService;

    public DevSessionController(AccountService accountService) {
        this.accountService = accountService;
    }

    // REMOVE LATER - UNTIL LOGIN IS COMPLETE
    @PostMapping("/api/session/dev")
    public DevSessionDto createDevSession(HttpServletRequest request) {
        return signIn(request, accountService.createDevSession());
    }

    // REMOVE LATER - UNTIL LOGIN IS COMPLETE. Fixed "Demo Viewer" account used by the /demo page.
    @PostMapping("/api/session/dev/viewer")
    public DevSessionDto createDevViewerSession(HttpServletRequest request) {
        return signIn(request, accountService.createDevViewerSession());
    }

    @GetMapping("/api/session/current")
    public Map<String, Long> getCurrentSessionDetails(HttpSession session) {
        Long userId = CurrentUser.find(session)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No active user session"));
        if (!(session.getAttribute(CurrentUser.CHANNEL_ID) instanceof Long channelId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No active channel session");
        }
        return Map.of("userId", userId, "channelId", channelId);
    }

    private DevSessionDto signIn(HttpServletRequest request, DevSessionDto devSession) {
        AccountEntity account = accountService.requireAccount(devSession.userId());
        CurrentUser.signIn(request, devSession.userId(), devSession.channelId(), AuthService.displayName(account));
        return devSession;
    }
}
