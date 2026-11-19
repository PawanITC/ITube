package ITubeBackend.auth;

import ITubeBackend.dto.accounts.AuthResponse;
import ITubeBackend.security.AccessTokens;
import ITubeBackend.security.SignedInUser;
import ITubeBackend.service.account.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Signs a browser in: a short-lived access token (read by every service) plus a refresh token (read only by
 * identity-service), both in HttpOnly cookies. See docs/AUTH.md.
 */
@Service
public class SignIn {
    private final AccessTokens accessTokens;
    private final RefreshTokens refreshTokens;
    private final AuthCookies cookies;
    private final AuthService authService;

    public SignIn(AccessTokens accessTokens, RefreshTokens refreshTokens, AuthCookies cookies, AuthService authService) {
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.authService = authService;
    }

    /** New sign-in (password, Cognito, dev session): a new refresh token family. */
    public void start(HttpServletResponse response, Long userId, Long channelId, String displayName) {
        cookies.setAccessToken(response, accessTokens.issue(new SignedInUser(userId, channelId, displayName)));
        cookies.setRefreshToken(response, refreshTokens.create(userId));
    }

    public void start(HttpServletResponse response, AuthResponse user) {
        start(response, user.userId(), user.channelId(), displayName(user));
    }

    /**
     * Swaps the refresh token for a new pair. The user's details are read again, so a channel created since the last
     * token (e.g. by the catalog after an outage at registration) is picked up.
     */
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String token = AuthCookies.read(request, AuthCookies.REFRESH)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again"));
        RefreshTokens.Rotation rotation;
        try {
            rotation = refreshTokens.rotate(token);
        } catch (ResponseStatusException invalid) {
            cookies.clearTokens(response);
            throw invalid;
        }
        AuthResponse user = authService.currentUser(rotation.userId());
        cookies.setAccessToken(response, accessTokens.issue(
                new SignedInUser(user.userId(), user.channelId(), displayName(user))));
        cookies.setRefreshToken(response, rotation.token());
        return user;
    }

    /**
     * Revokes this browser's refresh token and clears the cookies. An access token already copied elsewhere stays
     * valid until it expires (at most itube.auth.access-token-ttl).
     */
    public void end(HttpServletRequest request, HttpServletResponse response) {
        AuthCookies.read(request, AuthCookies.REFRESH).ifPresent(refreshTokens::revoke);
        cookies.clearTokens(response);
    }

    private static String displayName(AuthResponse user) {
        return (user.firstname() + " " + user.lastname()).trim();
    }
}
