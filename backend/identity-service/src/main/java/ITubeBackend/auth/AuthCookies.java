package ITubeBackend.auth;

import ITubeBackend.security.AccessTokens;
import ITubeBackend.security.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * The browser's auth cookies. All are HttpOnly, so page scripts (and XSS) can't read them.
 * <ul>
 *   <li>{@code ITUBE_AT}: the access token, sent to every service ({@code Path=/}, {@code SameSite=Lax}, like the old
 *   session cookie).</li>
 *   <li>{@code ITUBE_RT}: the refresh token, sent only to {@code /api/auth} ({@code SameSite=Strict}).</li>
 *   <li>{@code ITUBE_OAUTH}: state of a Cognito sign-in in progress, sent only to the callback. {@code Lax}, because
 *   the callback is a top-level navigation from the Cognito domain.</li>
 * </ul>
 */
@Component
public class AuthCookies {
    public static final String REFRESH = "ITUBE_RT";
    public static final String OAUTH = "ITUBE_OAUTH";
    static final String REFRESH_PATH = "/api/auth";
    static final String OAUTH_PATH = "/api/auth/oauth/cognito";

    private final AuthProperties properties;

    public AuthCookies(AuthProperties properties) {
        this.properties = properties;
    }

    public void setAccessToken(HttpServletResponse response, String token) {
        add(response, AccessTokens.COOKIE, token, "/", "Lax", properties.getAccessTokenTtl());
    }

    public void setRefreshToken(HttpServletResponse response, String token) {
        add(response, REFRESH, token, REFRESH_PATH, "Strict", properties.getRefreshTokenTtl());
    }

    public void setOAuthState(HttpServletResponse response, String value, Duration ttl) {
        add(response, OAUTH, value, OAUTH_PATH, "Lax", ttl);
    }

    public void clearTokens(HttpServletResponse response) {
        add(response, AccessTokens.COOKIE, "", "/", "Lax", Duration.ZERO);
        add(response, REFRESH, "", REFRESH_PATH, "Strict", Duration.ZERO);
    }

    public void clearOAuthState(HttpServletResponse response) {
        add(response, OAUTH, "", OAUTH_PATH, "Lax", Duration.ZERO);
    }

    public static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && !cookie.getValue().isBlank()) return Optional.of(cookie.getValue());
        }
        return Optional.empty();
    }

    private void add(HttpServletResponse response, String name, String value, String path, String sameSite,
                     Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .path(path)
                .sameSite(sameSite)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
