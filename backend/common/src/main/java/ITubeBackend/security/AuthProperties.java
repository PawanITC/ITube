package ITubeBackend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for ITube access tokens ({@code itube.auth.*}, defaults in itube-defaults.properties).
 * <p>
 * identity-service signs tokens with an RSA private key ({@code jwt.private-key}). Every other service only verifies
 * them, with the public keys it reads from identity's JWKS ({@code jwt.jwks-uri}) and caches, so a service never
 * needs the private key and keeps verifying while identity-service is down.
 */
@ConfigurationProperties(prefix = "itube.auth")
public class AuthProperties {
    /** {@code iss} claim of every token, checked on verify. */
    private String issuer = "itube";
    /** {@code aud} claim of every token, checked on verify. */
    private String audience = "itube";
    /** How long an access token is accepted. Short, because it can't be revoked before it expires. */
    private Duration accessTokenTtl = Duration.ofMinutes(15);
    /** How long a refresh token can be used to get a new access token (identity-service). */
    private Duration refreshTokenTtl = Duration.ofDays(30);
    /** Secure flag on the auth cookies. On wherever the site is served over HTTPS. */
    private boolean cookieSecure = false;
    private final Jwt jwt = new Jwt();

    public static class Jwt {
        /**
         * PKCS#8 PEM ("-----BEGIN PRIVATE KEY-----") RSA key that signs tokens. identity-service only. Blank =
         * generate a temporary key on first use (one-process local development and tests; tokens stop working when
         * the process restarts, and replicas would each have a different key).
         */
        private String privateKey = "";
        /** Public key (X.509 PEM) of the previous signing key, still published in the JWKS during a key rotation. */
        private String previousPublicKey = "";
        /** Where verifying services read the public keys. Blank = only the local key is trusted. */
        private String jwksUri = "";
        /** Refuse to start without {@link #privateKey} (identity-service in Kubernetes, which runs replicas). */
        private boolean requireConfiguredKey = false;

        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        public String getPreviousPublicKey() { return previousPublicKey; }
        public void setPreviousPublicKey(String previousPublicKey) { this.previousPublicKey = previousPublicKey; }
        public String getJwksUri() { return jwksUri; }
        public void setJwksUri(String jwksUri) { this.jwksUri = jwksUri; }
        public boolean isRequireConfiguredKey() { return requireConfiguredKey; }
        public void setRequireConfiguredKey(boolean requireConfiguredKey) { this.requireConfiguredKey = requireConfiguredKey; }
    }

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public Duration getAccessTokenTtl() { return accessTokenTtl; }
    public void setAccessTokenTtl(Duration accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }
    public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
    public void setRefreshTokenTtl(Duration refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
    public Jwt getJwt() { return jwt; }
}
