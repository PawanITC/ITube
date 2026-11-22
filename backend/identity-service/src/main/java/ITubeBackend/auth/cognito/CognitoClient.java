package ITubeBackend.auth.cognito;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Talks to the Cognito user pool: builds the managed login URL, exchanges the authorization code for tokens, and
 * verifies the ID token (signature against the pool's JWKS, issuer, audience = our client id, token_use = id, nonce).
 */
@Component
public class CognitoClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final CognitoProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile JWKSource<SecurityContext> keys;

    public CognitoClient(CognitoProperties properties) {
        this.properties = properties;
    }

    public static class CognitoException extends RuntimeException {
        private final String code;

        CognitoException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        CognitoException(String code, String message) {
            this(code, message, null);
        }

        /** Short code for the login page ("?error=..."), never details. */
        public String code() {
            return code;
        }
    }

    public URI authorizationUrl(String state, String nonce, String codeChallenge) {
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(properties.authorizeEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("scope", properties.getScopes())
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256");
        if (!properties.getIdentityProvider().isBlank()) {
            url.queryParam("identity_provider", properties.getIdentityProvider());
        }
        return url.encode().build().toUri();
    }

    /** Authorization code -> ID token (with PKCE). Only the ID token is used; Cognito's access token is not kept. */
    public String exchangeCode(String code, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", properties.getClientId());
        form.put("code", code);
        form.put("redirect_uri", properties.getRedirectUri());
        form.put("code_verifier", codeVerifier);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(properties.tokenEndpoint()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)));
        if (!properties.getClientSecret().isBlank()) {
            String basic = Base64.getEncoder().encodeToString((urlEncode(properties.getClientId()) + ":"
                    + urlEncode(properties.getClientSecret())).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + basic);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new CognitoException("unavailable", "Cognito token endpoint unreachable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CognitoException("unavailable", "Interrupted calling Cognito", exception);
        }
        if (response.statusCode() != 200) {
            // Typically invalid_grant: the code was already used or has expired (they live ~5 minutes)
            throw new CognitoException("code", "Cognito rejected the authorization code: HTTP " + response.statusCode());
        }
        try {
            Map<String, Object> body = JSONObjectUtils.parse(response.body());
            Object idToken = body.get("id_token");
            if (!(idToken instanceof String token) || token.isBlank()) {
                throw new CognitoException("code", "Cognito returned no id_token (is the openid scope allowed?)");
            }
            return token;
        } catch (ParseException exception) {
            throw new CognitoException("code", "Cognito returned an unreadable token response", exception);
        }
    }

    public JWTClaimsSet verifyIdToken(String idToken, String expectedNonce) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys()));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(properties.getClientId(),
                new JWTClaimsSet.Builder()
                        .issuer(properties.issuer())
                        .claim("token_use", "id")
                        .claim("nonce", expectedNonce)
                        .build(),
                Set.of("sub", "exp", "iat")));
        try {
            return processor.process(idToken, null);
        } catch (ParseException | BadJOSEException | JOSEException exception) {
            throw new CognitoException("token", "Cognito ID token failed verification: " + exception.getMessage(), exception);
        }
    }

    private JWKSource<SecurityContext> keys() {
        JWKSource<SecurityContext> source = keys;
        if (source != null) return source;
        synchronized (this) {
            if (keys == null) {
                try {
                    keys = JWKSourceBuilder.<SecurityContext>create(URI.create(properties.jwksUri()).toURL(),
                                    new DefaultResourceRetriever(2000, 3000, 256 * 1024))
                            .cache(TimeUnit.HOURS.toMillis(6), 3000)
                            .rateLimited(30_000)
                            .retrying(true)
                            .build();
                } catch (java.net.MalformedURLException exception) {
                    throw new IllegalStateException("Invalid Cognito region or user pool id", exception);
                }
            }
            return keys;
        }
    }

    private static String formEncode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
