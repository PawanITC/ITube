package ITubeBackend.controller;

import ITubeBackend.dto.accounts.AuthResponse;
import ITubeBackend.dto.accounts.LoginRequest;
import ITubeBackend.dto.accounts.RegisterRequest;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.account.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "Register, sign in and sign out")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        AuthResponse user = authService.register(body);
        signIn(request, user);
        return user;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        AuthResponse user = authService.login(body);
        signIn(request, user);
        return user;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AuthResponse me(HttpSession session) {
        return authService.currentUser(CurrentUser.require(session));
    }

    private static void signIn(HttpServletRequest request, AuthResponse user) {
        HttpSession session = request.getSession(true);
        // New session id on sign-in so a session id planted before login can't be reused (session fixation).
        request.changeSessionId();
        session.setAttribute("userId", user.userId());
        if (user.channelId() != null) session.setAttribute("channelId", user.channelId());
        else session.removeAttribute("channelId");
    }
}
