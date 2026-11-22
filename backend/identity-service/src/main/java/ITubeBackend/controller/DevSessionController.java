package ITubeBackend.controller;

import ITubeBackend.auth.SignIn;
import ITubeBackend.dto.accounts.DevSessionDto;
import ITubeBackend.entities.AccountEntity;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.security.SignedInUser;
import ITubeBackend.service.account.AccountService;
import ITubeBackend.service.account.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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
    private final SignIn signIn;

    public DevSessionController(AccountService accountService, SignIn signIn) {
        this.accountService = accountService;
        this.signIn = signIn;
    }

    // REMOVE LATER - UNTIL LOGIN IS COMPLETE
    @PostMapping("/api/session/dev")
    public DevSessionDto createDevSession(HttpServletResponse response) {
        return signIn(response, accountService.createDevSession());
    }

    // REMOVE LATER - UNTIL LOGIN IS COMPLETE. Fixed "Demo Viewer" account used by the /demo page.
    @PostMapping("/api/session/dev/viewer")
    public DevSessionDto createDevViewerSession(HttpServletResponse response) {
        return signIn(response, accountService.createDevViewerSession());
    }

    @GetMapping("/api/session/current")
    public Map<String, Long> getCurrentSessionDetails() {
        SignedInUser user = CurrentUser.user()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No active user session"));
        if (user.channelId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No active channel session");
        }
        return Map.of("userId", user.userId(), "channelId", user.channelId());
    }

    private DevSessionDto signIn(HttpServletResponse response, DevSessionDto devSession) {
        AccountEntity account = accountService.requireAccount(devSession.userId());
        signIn.start(response, devSession.userId(), devSession.channelId(), AuthService.displayName(account));
        return devSession;
    }
}
