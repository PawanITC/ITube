package ITubeBackend.auth.cognito;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Amazon Cognito user pool used for "Continue with Cognito" ({@code itube.auth.cognito.*}). The values come from
 * deploy/terraform (outputs cognito_*). Sign-in uses the pool's managed login page with the OAuth 2.0 authorization
 * code flow and PKCE; Cognito can in turn offer Google, Apple, SAML... as identity providers on that page.
 */
@ConfigurationProperties(prefix = "itube.auth.cognito")
public class CognitoProperties {
    private boolean enabled = false;
    /** e.g. eu-west-2 */
    private String region = "";
    /** e.g. eu-west-2_AbCdEf123 */
    private String userPoolId = "";
    /** App client id. */
    private String clientId = "";
    /** Blank for a public client (PKCE only, the Terraform default); set for a confidential client. */
    private String clientSecret = "";
    /** Managed login domain, e.g. https://itube-dev.auth.eu-west-2.amazoncognito.com */
    private String domain = "";
    /** Must be one of the app client's callback URLs: https://<site>/api/auth/oauth/cognito/callback */
    private String redirectUri = "";
    /** Where the browser goes after signing in, e.g. https://<site>/ */
    private String postLoginRedirect = "/";
    /** Where the browser goes when sign-in fails; "?error=<code>" is appended. */
    private String errorRedirect = "/login";
    private String scopes = "openid email profile";
    /** Optional: send straight to one provider configured in the pool (e.g. Google), skipping the choice. */
    private String identityProvider = "";

    public boolean isConfigured() {
        return enabled && !region.isBlank() && !userPoolId.isBlank() && !clientId.isBlank() && !domain.isBlank()
                && !redirectUri.isBlank();
    }

    public String issuer() {
        return "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId;
    }

    public String jwksUri() {
        return issuer() + "/.well-known/jwks.json";
    }

    public String authorizeEndpoint() {
        return trimSlash(domain) + "/oauth2/authorize";
    }

    public String tokenEndpoint() {
        return trimSlash(domain) + "/oauth2/token";
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getUserPoolId() { return userPoolId; }
    public void setUserPoolId(String userPoolId) { this.userPoolId = userPoolId; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    public String getPostLoginRedirect() { return postLoginRedirect; }
    public void setPostLoginRedirect(String postLoginRedirect) { this.postLoginRedirect = postLoginRedirect; }
    public String getErrorRedirect() { return errorRedirect; }
    public void setErrorRedirect(String errorRedirect) { this.errorRedirect = errorRedirect; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public String getIdentityProvider() { return identityProvider; }
    public void setIdentityProvider(String identityProvider) { this.identityProvider = identityProvider; }
}
