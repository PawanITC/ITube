package ITubeBackend.controller;

import ITubeBackend.auth.SignIn;
import ITubeBackend.auth.cognito.CognitoProperties;
import ITubeBackend.dto.accounts.AuthResponse;
import ITubeBackend.dto.accounts.LoginRequest;
import ITubeBackend.dto.accounts.RegisterRequest;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.account.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Register, sign in, refresh and sign out. Signing in sets two HttpOnly cookies: the access token (a JWT every service
 * verifies on its own) and the refresh token (identity-service only). See docs/AUTH.md.
 */
@Tag(name = "Auth", description = "Register, sign in, refresh the access token and sign out")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SignIn signIn;
    private final CognitoProperties cognito;

    public AuthController(AuthService authService, SignIn signIn, CognitoProperties cognito) {
        this.authService = authService;
        this.signIn = signIn;
        this.cognito = cognito;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest body, HttpServletResponse response) {
        AuthResponse user = authService.register(body);
        signIn.start(response, user);
        return user;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest body, HttpServletResponse response) {
        AuthResponse user = authService.login(body);
        signIn.start(response, user);
        return user;
    }

    @Operation(summary = "New access token from the refresh token cookie (the refresh token is rotated)")
    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        return signIn.refresh(request, response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        signIn.end(request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AuthResponse me() {
        return authService.currentUser(CurrentUser.require());
    }

    @Operation(summary = "Which sign-in options the login page should offer")
    @GetMapping("/providers")
    public Map<String, Boolean> providers() {
        return Map.of("password", true, "cognito", cognito.isConfigured());
    }
}
