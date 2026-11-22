package ITubeBackend.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Issues and verifies ITube access tokens: RS256-signed JWTs (header {@code typ: at+jwt}) carrying the user id
 * ({@code sub}), primary channel ({@code cid}) and display name ({@code name}).
 * <p>
 * Signing happens only in identity-service. Every service verifies locally: with the local key when it has one,
 * otherwise with the public keys from identity's JWKS, fetched once and cached (kept through identity outages, and
 * re-fetched when a token names an unknown key id, which is how key rotation reaches the other services).
 * <p>
 * The same keys also sign identity's short-lived internal tokens (e.g. the OAuth state cookie) through
 * {@link #sign}/{@link #verify}; their own {@code typ} keeps them from ever being accepted as access tokens.
 */
public class AccessTokens {
    public static final String COOKIE = "ITUBE_AT";
    public static final JOSEObjectType ACCESS_TOKEN_TYPE = new JOSEObjectType("at+jwt");
    private static final String CHANNEL_CLAIM = "cid";
    private static final String NAME_CLAIM = "name";
    private static final Logger log = LoggerFactory.getLogger(AccessTokens.class);

    private final AuthProperties properties;
    private final Clock clock;
    private final JWKSource<SecurityContext> remoteKeys;
    private final RSAKey previousKey;
    private volatile RSAKey signingKey;

    public AccessTokens(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        AuthProperties.Jwt jwt = properties.getJwt();
        if (!jwt.getPrivateKey().isBlank()) {
            signingKey = Pem.rsaSigningKey(jwt.getPrivateKey());
        } else if (jwt.isRequireConfiguredKey()) {
            throw new IllegalStateException("itube.auth.jwt.private-key (JWT_PRIVATE_KEY) must be set: replicas need "
                    + "the same signing key. Generate one with: openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048");
        }
        previousKey = jwt.getPreviousPublicKey().isBlank() ? null : Pem.rsaPublicKey(jwt.getPreviousPublicKey());
        remoteKeys = jwt.getJwksUri().isBlank() ? null : remoteJwks(jwt.getJwksUri());
    }

    /** A signed access token for this user, valid for {@code itube.auth.access-token-ttl}. */
    public String issue(SignedInUser user) {
        Instant now = clock.instant();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(properties.getIssuer())
                .audience(properties.getAudience())
                .subject(String.valueOf(user.userId()))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.getAccessTokenTtl())))
                .jwtID(UUID.randomUUID().toString());
        if (user.channelId() != null) claims.claim(CHANNEL_CLAIM, user.channelId());
        if (user.displayName() != null) claims.claim(NAME_CLAIM, user.displayName());
        return sign(ACCESS_TOKEN_TYPE, claims.build());
    }

    /** The user of a valid access token; empty when the token is malformed, forged, expired or of another kind. */
    public Optional<SignedInUser> verify(String token) {
        return verify(ACCESS_TOKEN_TYPE, token).flatMap(claims -> {
            try {
                Long userId = Long.valueOf(claims.getSubject());
                return Optional.of(new SignedInUser(userId, claims.getLongClaim(CHANNEL_CLAIM),
                        claims.getStringClaim(NAME_CLAIM)));
            } catch (NumberFormatException | java.text.ParseException exception) {
                return Optional.empty();
            }
        });
    }

    public Duration accessTokenTtl() {
        return properties.getAccessTokenTtl();
    }

    /** Signs any claims with the current key. {@code type} must be unique to the kind of token. */
    public String sign(JOSEObjectType type, JWTClaimsSet claims) {
        RSAKey key = signingKey();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(type).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Could not sign a token", exception);
        }
    }

    /**
     * Verifies the signature, {@code typ}, issuer, audience and expiry of a token made by {@link #sign} with the
     * same {@code type}. Tokens must carry {@code iss}, {@code aud}, {@code sub} and {@code exp}.
     */
    public Optional<JWTClaimsSet> verify(JOSEObjectType type, String token) {
        if (token == null || token.isBlank() || token.length() > 8192) return Optional.empty();
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(type));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, this::keysFor));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(properties.getAudience(),
                new JWTClaimsSet.Builder().issuer(properties.getIssuer()).build(), Set.of("sub", "exp", "iat")) {
            @Override
            protected Date currentTime() {
                return Date.from(clock.instant());
            }
        });
        try {
            return Optional.of(processor.process(token, null));
        } catch (java.text.ParseException | com.nimbusds.jose.proc.BadJOSEException | JOSEException exception) {
            log.debug("Rejected token: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    /** The public keys that verify tokens from this service (identity publishes them at /.well-known/jwks.json). */
    public JWKSet publicJwks() {
        List<JWK> keys = new ArrayList<>();
        keys.add(signingKey().toPublicJWK());
        if (previousKey != null) keys.add(previousKey);
        return new JWKSet(keys);
    }

    private List<JWK> keysFor(com.nimbusds.jose.jwk.JWKSelector selector, SecurityContext context)
            throws com.nimbusds.jose.KeySourceException {
        List<JWK> local = new ArrayList<>();
        if (signingKey != null) local.add(signingKey.toPublicJWK());
        if (previousKey != null) local.add(previousKey);
        List<JWK> matches = selector.select(new JWKSet(local));
        if (!matches.isEmpty() || remoteKeys == null) return matches;
        return remoteKeys.get(selector, context);
    }

    private RSAKey signingKey() {
        RSAKey key = signingKey;
        if (key != null) return key;
        synchronized (this) {
            if (signingKey == null) {
                log.warn("No itube.auth.jwt.private-key: signing with a temporary key. Tokens stop working on restart; "
                        + "set JWT_PRIVATE_KEY anywhere more than one process signs tokens.");
                try {
                    signingKey = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                            .keyIDFromThumbprint(true).generate();
                } catch (JOSEException exception) {
                    throw new IllegalStateException("Could not generate a signing key", exception);
                }
            }
            return signingKey;
        }
    }

    private static JWKSource<SecurityContext> remoteJwks(String uri) {
        try {
            // Same budget as other service calls: 1 s connect, 2 s read
            DefaultResourceRetriever retriever = new DefaultResourceRetriever(1000, 2000, 256 * 1024);
            return JWKSourceBuilder.<SecurityContext>create(URI.create(uri).toURL(), retriever)
                    .cache(TimeUnit.MINUTES.toMillis(15), 2000)
                    // identity-service down: keep using the keys we have rather than rejecting every user
                    .outageTolerant(TimeUnit.DAYS.toMillis(1))
                    // A token with an unknown key id re-fetches the JWKS, at most once every 30 s
                    .rateLimited(30_000)
                    .retrying(true)
                    .build();
        } catch (java.net.MalformedURLException | IllegalArgumentException exception) {
            throw new IllegalStateException("itube.auth.jwt.jwks-uri is not a valid URL: " + uri, exception);
        }
    }

    /** Minimal PEM reading (no BouncyCastle): PKCS#8 RSA private keys and X.509 public keys. */
    static final class Pem {
        private Pem() {
        }

        static RSAKey rsaSigningKey(String pem) {
            byte[] der = decode(pem, "PRIVATE KEY");
            try {
                KeyFactory factory = KeyFactory.getInstance("RSA");
                RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
                RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                        new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
                return new RSAKey.Builder(publicKey).privateKey(privateKey).keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.RS256).keyIDFromThumbprint().build();
            } catch (GeneralSecurityException | ClassCastException | JOSEException exception) {
                throw new IllegalStateException("itube.auth.jwt.private-key is not a PKCS#8 RSA private key", exception);
            }
        }

        static RSAKey rsaPublicKey(String pem) {
            byte[] der = decode(pem, "PUBLIC KEY");
            try {
                RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
                return new RSAKey.Builder(publicKey).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                        .keyIDFromThumbprint().build();
            } catch (GeneralSecurityException | ClassCastException | JOSEException exception) {
                throw new IllegalStateException("itube.auth.jwt.previous-public-key is not an RSA public key", exception);
            }
        }

        private static byte[] decode(String pem, String label) {
            String text = pem.replace("\\n", "\n").trim();
            if (text.contains("BEGIN RSA PRIVATE KEY")) {
                throw new IllegalStateException("The JWT key is PKCS#1; convert it with: "
                        + "openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem");
            }
            String body = text.replace("-----BEGIN " + label + "-----", "")
                    .replace("-----END " + label + "-----", "")
                    .replaceAll("\\s", "");
            try {
                return Base64.getDecoder().decode(body);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("The JWT key is not valid PEM (" + label + ")", exception);
            }
        }
    }
}
