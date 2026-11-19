package ITubeBackend.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How catalog/engagement/payments trust identity-service's tokens: they hold no key, only identity's JWKS URL.
 */
class AccessTokensTest {
    private HttpServer identity;

    @AfterEach
    void stop() {
        if (identity != null) identity.stop(0);
    }

    /** Stands in for identity-service: publishes the signer's public keys like JwksController does. */
    private String serveJwks(AccessTokens signer, AtomicInteger fetches) throws IOException {
        identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identity.createContext("/.well-known/jwks.json", exchange -> {
            fetches.incrementAndGet();
            byte[] body = signer.publicJwks().toString(true).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        identity.start();
        return "http://127.0.0.1:" + identity.getAddress().getPort() + "/.well-known/jwks.json";
    }

    private static AccessTokens verifierFor(String jwksUri) {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setJwksUri(jwksUri);
        return new AccessTokens(properties, Clock.systemUTC());
    }

    @Test
    void anotherServiceVerifiesWithTheFetchedPublicKeysAndKeepsThemWhileIdentityIsDown() throws IOException {
        AccessTokens identityTokens = new AccessTokens(new AuthProperties(), Clock.systemUTC());
        AtomicInteger fetches = new AtomicInteger();
        AccessTokens catalog = verifierFor(serveJwks(identityTokens, fetches));

        String token = identityTokens.issue(new SignedInUser(42L, 7L, "Ada Lovelace"));
        assertThat(catalog.verify(token)).contains(new SignedInUser(42L, 7L, "Ada Lovelace"));
        assertThat(catalog.verify(identityTokens.issue(new SignedInUser(43L, null, null)))).isPresent();
        assertThat(fetches.get()).isEqualTo(1); // cached

        identity.stop(0);
        identity = null;
        assertThat(catalog.verify(token)).isPresent();
    }

    @Test
    void tokensSignedWithAnyOtherKeyAreRejected() throws IOException {
        AccessTokens identityTokens = new AccessTokens(new AuthProperties(), Clock.systemUTC());
        AccessTokens catalog = verifierFor(serveJwks(identityTokens, new AtomicInteger()));
        AccessTokens attacker = new AccessTokens(new AuthProperties(), Clock.systemUTC());

        assertThat(catalog.verify(attacker.issue(new SignedInUser(1L, null, null)))).isEmpty();
        assertThat(catalog.verify("")).isEmpty();
        assertThat(catalog.verify("a.b.c")).isEmpty();
    }

    @Test
    void identityRefusesToStartWithoutAKeyWhenOneIsRequired() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setRequireConfiguredKey(true);
        assertThatThrownBy(() -> new AccessTokens(properties, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_PRIVATE_KEY");
    }

    @Test
    void readsAPkcs8PemKeySoReplicasShareIt() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setPrivateKey(pem);
        AccessTokens replicaA = new AccessTokens(properties, Clock.systemUTC());
        AccessTokens replicaB = new AccessTokens(properties, Clock.systemUTC());
        assertThat(replicaB.verify(replicaA.issue(new SignedInUser(5L, null, null)))).isPresent();
    }
}
