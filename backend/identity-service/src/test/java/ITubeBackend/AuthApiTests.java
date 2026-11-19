package ITubeBackend;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.dto.accounts.AuthResponse;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.security.AccessTokens;
import ITubeBackend.service.account.AccountEvents;
import ITubeBackend.service.account.AuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Register, login, the token cookies, refresh-token rotation and reuse detection, and linking Cognito users. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class AuthApiTests {
    @Autowired private MockMvc mvc;
    @Autowired private AccessTokens tokens;
    @Autowired private AuthService authService;
    @Autowired private TestClock clock;
    // catalog-service is another process: stand in for it with an in-memory "channel per user"
    @MockitoBean private CatalogClient catalog;
    @MockitoBean private AccountEvents accountEvents;
    private final ConcurrentHashMap<Long, ChannelDto> channels = new ConcurrentHashMap<>();
    private final AtomicLong channelIds = new AtomicLong(100);
    private String email;

    /** A clock the tests can move forward (token expiry, refresh-token reuse grace period). */
    static class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    @BeforeEach
    void setup() {
        email = "auth-" + UUID.randomUUID() + "@Example.com";
        when(catalog.ensureChannel(anyLong(), anyString())).thenAnswer(call -> channels.computeIfAbsent(call.getArgument(0),
                user -> new ChannelDto(channelIds.incrementAndGet(), user, call.getArgument(1))));
        when(catalog.channelsOwnedBy(any())).thenAnswer(call -> {
            ChannelDto channel = channels.get(call.<Long>getArgument(0));
            return channel == null ? List.of() : List.of(channel);
        });
    }

    private String registerBody(String password) {
        return """
                {"firstname":"Ada","lastname":"Lovelace","email":"%s","password":"%s","channelName":"Ada's Lab"}
                """.formatted(email, password);
    }

    private String loginBody(String loginEmail, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(loginEmail, password);
    }

    private MockHttpServletResponse register() throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("correct horse"))).andExpect(status().isCreated()).andReturn().getResponse();
    }

    private static Cookie cookie(MockHttpServletResponse response, String name) {
        Cookie cookie = response.getCookie(name);
        assertThat(cookie).as(name).isNotNull();
        return cookie;
    }

    private MockHttpServletResponse refresh(Cookie refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").cookie(refreshToken)).andReturn().getResponse();
    }

    @Test
    void registerSignsInWithHttpOnlyTokenCookiesAndCreatesAChannel() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("correct horse")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.firstname").value("Ada"))
                .andExpect(jsonPath("$.channelName").value("Ada's Lab"))
                .andExpect(jsonPath("$.channelId").isNumber())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse();

        Cookie access = cookie(response, AccessTokens.COOKIE);
        Cookie refresh = cookie(response, "ITUBE_RT");
        assertThat(access.isHttpOnly()).isTrue();
        assertThat(access.getPath()).isEqualTo("/");
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/api/auth");
        assertThat(response.getHeaders("Set-Cookie")).anyMatch(header -> header.startsWith("ITUBE_RT=") && header.contains("SameSite=Strict"));

        mvc.perform(get("/api/auth/me").cookie(access)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()));
        mvc.perform(get("/api/session/current").cookie(access)).andExpect(status().isOk())
                .andExpect(jsonPath("$.channelId").isNumber());
        // API clients send the same token as a bearer token
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access.getValue())).andExpect(status().isOk());
    }

    @Test
    void forgedOrExpiredAccessTokensAreRejected() throws Exception {
        String token = cookie(register(), AccessTokens.COOKIE).getValue();
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());

        clock.advance(tokens.accessTokenTtl().plusMinutes(2)); // past expiry and the 60 s clock-skew allowance
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        register();
        email = email.toUpperCase();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("another password"))).andExpect(status().isConflict());
    }

    @Test
    void registerValidatesInput() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("short"))).andExpect(status().isBadRequest());
        email = "not-an-email";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("correct horse"))).andExpect(status().isBadRequest());
    }

    @Test
    void loginChecksThePasswordAndLogoutRevokesTheRefreshToken() throws Exception {
        register();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(email, "wrong password"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("nobody-" + email, "correct horse"))).andExpect(status().isUnauthorized());

        MockHttpServletResponse login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("  " + email.toUpperCase() + " ", "correct horse")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelName").value("Ada's Lab"))
                .andReturn().getResponse();
        Cookie access = cookie(login, AccessTokens.COOKIE);
        Cookie refresh = cookie(login, "ITUBE_RT");
        mvc.perform(get("/api/auth/me").cookie(access)).andExpect(status().isOk());

        MockHttpServletResponse logout = mvc.perform(post("/api/auth/logout").cookie(refresh))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(cookie(logout, AccessTokens.COOKIE).getMaxAge()).isZero();
        assertThat(cookie(logout, "ITUBE_RT").getMaxAge()).isZero();
        assertThat(refresh(refresh).getStatus()).isEqualTo(401);
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesTheRefreshTokenAndPicksUpANewChannel() throws Exception {
        // The catalog was down at registration: no channel yet
        when(catalog.ensureChannel(anyLong(), anyString())).thenThrow(
                new ITubeBackend.common.client.DownstreamUnavailableException("catalog", new RuntimeException("down")));
        MockHttpServletResponse registered = register();
        assertThat(tokens.verify(cookie(registered, AccessTokens.COOKIE).getValue()).orElseThrow().channelId()).isNull();
        Long userId = tokens.verify(cookie(registered, AccessTokens.COOKIE).getValue()).orElseThrow().userId();

        // ...the catalog creates it later from account.created
        channels.put(userId, new ChannelDto(channelIds.incrementAndGet(), userId, "Ada Lovelace"));
        MockHttpServletResponse refreshed = refresh(cookie(registered, "ITUBE_RT"));
        assertThat(refreshed.getStatus()).isEqualTo(200);
        assertThat(tokens.verify(cookie(refreshed, AccessTokens.COOKIE).getValue()).orElseThrow().channelId())
                .isEqualTo(channels.get(userId).channelId());
        assertThat(cookie(refreshed, "ITUBE_RT").getValue()).isNotEqualTo(cookie(registered, "ITUBE_RT").getValue());
    }

    @Test
    void aReplayedRefreshTokenRevokesTheWholeFamily() throws Exception {
        Cookie first = cookie(register(), "ITUBE_RT");
        MockHttpServletResponse second = refresh(first);
        assertThat(second.getStatus()).isEqualTo(200);

        // Two tabs refreshing at the same moment: the old token still works within the grace period
        assertThat(refresh(first).getStatus()).isEqualTo(200);

        clock.advance(Duration.ofMinutes(1));
        Cookie current = cookie(refresh(cookie(second, "ITUBE_RT")), "ITUBE_RT");
        // Later, the first token comes back: it was copied. Everything in its family stops working.
        MockHttpServletResponse replay = refresh(first);
        assertThat(replay.getStatus()).isEqualTo(401);
        assertThat(cookie(replay, "ITUBE_RT").getMaxAge()).isZero();
        assertThat(refresh(current).getStatus()).isEqualTo(401);
    }

    @Test
    void refreshTokensExpire() throws Exception {
        Cookie refreshToken = cookie(register(), "ITUBE_RT");
        clock.advance(Duration.ofDays(31));
        assertThat(refresh(refreshToken).getStatus()).isEqualTo(401);
        assertThat(refresh(new Cookie("ITUBE_RT", "made-up")).getStatus()).isEqualTo(401);
        mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    void jwksPublishesOnlyThePublicKey() throws Exception {
        mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void aNewCognitoUserGetsAnAccountWithoutPasswordAndAChannel() throws Exception {
        String subject = UUID.randomUUID().toString();
        AuthResponse first = authService.externalSignIn(profile(subject, email, true));
        assertThat(first.firstname()).isEqualTo("Grace");
        assertThat(first.channelId()).isNotNull();
        // Same Cognito user again: same account
        assertThat(authService.externalSignIn(profile(subject, "changed-" + email, true)).userId()).isEqualTo(first.userId());
        // The account has no password, so password login is refused (like a wrong password)
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(email, "anything at all"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aCognitoUserWithAVerifiedEmailIsLinkedToTheExistingAccount() throws Exception {
        Long existing = tokens.verify(cookie(register(), AccessTokens.COOKIE).getValue()).orElseThrow().userId();
        AuthResponse linked = authService.externalSignIn(profile(UUID.randomUUID().toString(), email.toUpperCase(), true));
        assertThat(linked.userId()).isEqualTo(existing);
        assertThat(linked.firstname()).isEqualTo("Ada");
    }

    @Test
    void unverifiedEmailsAreNeverTrusted() throws Exception {
        register();
        assertThatThrownBy(() -> authService.externalSignIn(profile(UUID.randomUUID().toString(), email, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Verify your email");
    }

    private static AuthService.ExternalProfile profile(String subject, String email, boolean verified) {
        return new AuthService.ExternalProfile("cognito", subject, email, verified, "Grace", "Hopper");
    }
}
