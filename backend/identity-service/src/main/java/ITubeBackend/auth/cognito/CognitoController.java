package ITubeBackend.auth.cognito;

import ITubeBackend.auth.AuthCookies;
import ITubeBackend.auth.SignIn;
import ITubeBackend.dto.accounts.AuthResponse;
import ITubeBackend.security.AccessTokens;
import ITubeBackend.security.AuthProperties;
import ITubeBackend.service.account.AuthService;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jwt.JWTClaimsSet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * "Continue with Cognito": OAuth 2.0 authorization code flow with PKCE against the Cognito user pool's managed login.
 * <ol>
 *   <li>{@code GET /api/auth/oauth/cognito/authorize}: random state, nonce and PKCE verifier go into a short-lived signed
 *   cookie; the browser is sent to Cognito.</li>
 *   <li>Cognito sends the browser back to {@code /callback?code&state}. The state must match the cookie (CSRF), the
 *   code is exchanged with the PKCE verifier, and the ID token is verified (signature, issuer, audience, nonce).</li>
 *   <li>The Cognito user is linked to an ITube account ({@link AuthService#externalSignIn}) and signed in with the
 *   usual ITube tokens. Cognito's own tokens are not kept: from here on it's a normal ITube sign-in.</li>
 * </ol>
 */
@Tag(name = "Auth", description = "Register, sign in, refresh the access token and sign out")
@RestController
@RequestMapping("/api/auth/oauth/cognito")
public class CognitoController {
    static final String PROVIDER = "cognito";
    private static final JOSEObjectType STATE_TYPE = new JOSEObjectType("itube-oauth-state+jwt");
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(CognitoController.class);

    private final CognitoProperties properties;
    private final CognitoClient cognito;
    private final AccessTokens tokens;
    private final AuthProperties authProperties;
    private final AuthCookies cookies;
    private final AuthService authService;
    private final SignIn signIn;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public CognitoController(CognitoProperties properties, CognitoClient cognito, AccessTokens tokens,
                             AuthProperties authProperties, AuthCookies cookies, AuthService authService, SignIn signIn,
                             Clock clock) {
        this.properties = properties;
        this.cognito = cognito;
        this.tokens = tokens;
        this.authProperties = authProperties;
        this.cookies = cookies;
        this.authService = authService;
        this.signIn = signIn;
        this.clock = clock;
    }

    @Operation(summary = "Start signing in with Cognito (redirects to the Cognito login page)")
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(HttpServletResponse response) {
        requireConfigured();
        String state = randomToken();
        String nonce = randomToken();
        String verifier = randomToken() + randomToken(); // 86 characters, within PKCE's 43-128
        Instant now = clock.instant();
        String stateCookie = tokens.sign(STATE_TYPE, new JWTClaimsSet.Builder()
                .issuer(authProperties.getIssuer())
                .audience(authProperties.getAudience())
                .subject(PROVIDER)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(STATE_TTL)))
                .claim("state", state)
                .claim("nonce", nonce)
                .claim("verifier", verifier)
                .build());
        cookies.setOAuthState(response, stateCookie, STATE_TTL);
        return redirect(cognito.authorizationUrl(state, nonce, challenge(verifier)));
    }

    @Operation(summary = "Cognito redirects here after sign-in; signs the user in and redirects to the site")
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         HttpServletRequest request, HttpServletResponse response) {
        requireConfigured();
        String stateCookie = AuthCookies.read(request, AuthCookies.OAUTH).orElse(null);
        cookies.clearOAuthState(response);
        if (error != null) {
            // e.g. access_denied when the user cancels on the Cognito page
            log.info("Cognito sign-in ended with error={}", error);
            return failed("cancelled");
        }
        JWTClaimsSet saved = tokens.verify(STATE_TYPE, stateCookie).orElse(null);
        if (saved == null || code == null || state == null || !constantTimeEquals(state, claim(saved, "state"))) {
            // Expired (over 10 minutes), opened in another browser, or a forged callback (CSRF)
            return failed("state");
        }
        try {
            String idToken = cognito.exchangeCode(code, claim(saved, "verifier"));
            JWTClaimsSet claims = cognito.verifyIdToken(idToken, claim(saved, "nonce"));
            AuthResponse user = authService.externalSignIn(profile(claims));
            signIn.start(response, user);
            return redirect(URI.create(properties.getPostLoginRedirect()));
        } catch (CognitoClient.CognitoException exception) {
            log.warn("Cognito sign-in failed: {}", exception.getMessage());
            return failed(exception.code());
        } catch (ResponseStatusException exception) {
            log.info("Cognito sign-in refused: {}", exception.getReason());
            return failed(exception.getStatusCode() == HttpStatus.FORBIDDEN ? "unverified" : "account");
        }
    }

    static AuthService.ExternalProfile profile(JWTClaimsSet claims) {
        String given = string(claims, "given_name");
        String family = string(claims, "family_name");
        String email = string(claims, "email");
        if (given == null && family == null) {
            String name = string(claims, "name");
            if (name != null && !name.isBlank()) {
                int space = name.trim().indexOf(' ');
                given = space < 0 ? name.trim() : name.trim().substring(0, space);
                family = space < 0 ? "" : name.trim().substring(space + 1);
            } else if (email != null) {
                given = email.substring(0, Math.max(0, email.indexOf('@')));
            }
        }
        // Cognito sends email_verified as a boolean, but as the string "true" for some federated users
        Object verified = claims.getClaim("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equalsIgnoreCase(String.valueOf(verified));
        return new AuthService.ExternalProfile(PROVIDER, claims.getSubject(), email, emailVerified,
                given == null ? "" : given, family == null ? "" : family);
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cognito sign-in is not configured");
        }
    }

    private ResponseEntity<Void> failed(String code) {
        return redirect(UriComponentsBuilder.fromUriString(properties.getErrorRedirect())
                .queryParam("error", "cognito-" + code).build().toUri());
    }

    private static ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String claim(JWTClaimsSet claims, String name) {
        String value = string(claims, name);
        return value == null ? "" : value;
    }

    private static String string(JWTClaimsSet claims, String name) {
        try {
            return claims.getStringClaim(name);
        } catch (ParseException exception) {
            return null;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
