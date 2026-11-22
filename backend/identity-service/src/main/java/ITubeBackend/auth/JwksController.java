package ITubeBackend.auth;

import ITubeBackend.security.AccessTokens;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Public keys that verify ITube access tokens (RFC 7517). The other services fetch this and cache it. Only public
 * keys are published; the gateway doesn't route it, since only services inside the cluster need it.
 */
@Hidden
@RestController
public class JwksController {
    private final AccessTokens accessTokens;

    public JwksController(AccessTokens accessTokens) {
        this.accessTokens = accessTokens;
    }

    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)))
                .body(accessTokens.publicJwks().toJSONObject(true));
    }
}
