# Signing in: access tokens, refresh tokens and Cognito

Every ITube service needs to know who is making a request. Before, that was a Redis-backed session shared by all
services: every request read Redis. Now identity-service signs a short-lived **access token** (a JWT) and every
service checks the signature by itself. No request reads Redis or calls identity-service to find out who the user is.

## The tokens

| | Access token | Refresh token |
|---|---|---|
| What | RS256-signed JWT: `sub` = user id, `cid` = channel id, `name` = display name, `exp`, `jti` | 256 random bits, opaque |
| Where (browser) | HttpOnly cookie `ITUBE_AT`, `Path=/`, `SameSite=Lax` | HttpOnly cookie `ITUBE_RT`, `Path=/api/auth`, `SameSite=Strict` |
| API clients | `Authorization: Bearer <token>` (wins over the cookie) | not used |
| Lifetime | 15 minutes (`itube.auth.access-token-ttl`) | 30 days (`itube.auth.refresh-token-ttl`) |
| Checked by | every service: signature, `typ: at+jwt`, issuer, audience, expiry | identity-service: SHA-256 lookup in `identity.refresh_tokens` |
| Revocable | no, it just expires | yes: sign-out, or reuse detection |

Page scripts can read neither cookie, so an XSS bug can't steal them. `SameSite` plus the cookie paths keep the
same CSRF protection the session cookie had.

### Keys

- identity-service signs with an RSA private key: `JWT_PRIVATE_KEY` (PKCS#8 PEM). In Kubernetes it comes from the
  Secret `itube-secrets/jwt-private-key`, and `JWT_REQUIRE_CONFIGURED_KEY=true` stops identity from starting without
  it, since every replica must sign with the same key.
- identity publishes the public key at `GET /.well-known/jwks.json` (cluster-internal: the gateway doesn't route it).
- catalog, engagement and payments fetch that JWKS once, cache it for 15 minutes and keep using it for up to a day
  if identity-service is down. A token signed with a key they don't know triggers one re-fetch, at most every 30 s.
- Locally, with no key set, identity makes a temporary one at start-up. Tokens then stop working when identity
  restarts; the frontend refreshes and carries on, since refresh tokens are in the database.

Code: [AccessTokens](../backend/common/src/main/java/ITubeBackend/security/AccessTokens.java) (sign + verify),
[AuthenticationConfig](../backend/common/src/main/java/ITubeBackend/security/AuthenticationConfig.java) (the filter),
[CurrentUser](../backend/common/src/main/java/ITubeBackend/security/CurrentUser.java) (what controllers call).

### Refresh and rotation

When any API call answers 401, the frontend ([api/session.js](../frontend/src/api/session.js)) calls
`POST /api/auth/refresh` once and retries the call. Parallel 401s share one refresh.

Each refresh **rotates** the refresh token: the old one is marked used and a new one joins the same *family*
(one family per sign-in). If a used token comes back more than 30 seconds later, someone copied it: the whole family
is deleted, which signs out both the copy and the real user. The 30 seconds cover two tabs refreshing at the same
moment. A refresh also re-reads the user, so a channel created after sign-in (the catalog was down at registration)
appears in the next access token.

### Signing out

`POST /api/auth/logout` deletes the refresh token's family and clears both cookies. An access token that was
copied elsewhere still works until it expires, at most 15 minutes. That is the price of not checking a store on
every request. If you need instant revocation, add a small deny-list of `jti` values (for example in Redis, read only
for sensitive actions) or shorten the TTL.

## Continue with Cognito

The sign-in page shows **Continue with Cognito** when identity-service has a Cognito user pool configured
(`GET /api/auth/providers`). The flow is OAuth 2.0 authorization code with PKCE, run by identity-service
([CognitoController](../backend/identity-service/src/main/java/ITubeBackend/auth/cognito/CognitoController.java)):

1. `GET /api/auth/oauth/cognito/authorize` creates a random `state`, `nonce` and PKCE verifier, keeps them in a signed
   10-minute cookie (`ITUBE_OAUTH`), and redirects to the pool's hosted login page.
2. The user signs in or signs up at Cognito, which verifies their email.
3. Cognito redirects to `/api/auth/oauth/cognito/callback?code&state`. identity-service checks `state` against the
   cookie (CSRF), exchanges the code with the PKCE verifier, and verifies the ID token: signature (Cognito's JWKS),
   issuer, audience = our client id, `token_use=id`, and `nonce`.
4. The Cognito user (`sub`) is linked to an ITube account in `identity.external_identities`:
   - already linked: that account;
   - otherwise the account with the same **verified** email (an unverified email is refused, or anyone could sign
     up at Cognito with someone else's address and take over their account);
   - otherwise a new account without a password, plus its channel.
5. identity-service signs the user in with the usual ITube tokens and redirects to the site. Cognito's own tokens
   aren't kept.

Failures redirect to `/login?error=cognito-<reason>` and the page explains them. An account created through Cognito
has no password, so password sign-in answers "Incorrect email or password" for it.

Signing out of ITube doesn't sign the browser out of the Cognito hosted page. The next "Continue with Cognito" can
skip the password prompt until Cognito's own session ends.

### Setting it up

1. `terraform apply` in [deploy/terraform](../deploy/terraform) creates the user pool, its hosted login domain, a
   public app client (code flow + PKCE, no secret) and the ConfigMap `itube-cognito`, which identity-service reads.
   Callback URLs: variable `cognito_callback_urls` (the site and `http://localhost:3000` by default).
2. Restart identity-service (`kubectl -n itube rollout restart deployment/identity`) to pick up the ConfigMap.

Locally, set the same values as environment variables for identity-service (`terraform output` shows them):

```
COGNITO_ENABLED=true
COGNITO_REGION=eu-west-2
COGNITO_USER_POOL_ID=<cognito_user_pool_id>
COGNITO_CLIENT_ID=<cognito_client_id>
COGNITO_DOMAIN=<cognito_domain>
```

Google, Apple, Facebook or a company's SAML/OIDC login can be added to the pool as identity providers without
changing ITube. Set `COGNITO_IDENTITY_PROVIDER` to skip Cognito's chooser and go straight to one of them.

## Rotating the signing key

1. Generate a new key: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out new.pem`.
2. Put the **old** key's public half in `ITUBE_JWT_PREVIOUS_PUBLIC_KEY`
   (`openssl pkey -in old.pem -pubout`) and the new key in `ITUBE_JWT_PRIVATE_KEY`, then deploy. identity signs with
   the new key and publishes both public keys; the other services fetch the new one the first time they see its key id.
3. After 15 minutes (the access token lifetime) every old token has expired: remove `ITUBE_JWT_PREVIOUS_PUBLIC_KEY`.

Users stay signed in throughout: refresh tokens don't depend on the signing key.
