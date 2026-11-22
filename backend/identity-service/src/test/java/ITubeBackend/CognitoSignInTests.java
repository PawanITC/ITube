package ITubeBackend;

import ITubeBackend.auth.cognito.CognitoClient;
import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.security.AccessTokens;
import ITubeBackend.service.account.AccountEvents;
import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Cognito redirect flow: state cookie, CSRF check, code exchange and sign-in. Cognito itself is mocked. */
@SpringBootTest(properties = {
        "itube.auth.cognito.enabled=true",
        "itube.auth.cognito.region=eu-west-2",
        "itube.auth.cognito.user-pool-id=eu-west-2_Test",
        "itube.auth.cognito.client-id=test-client",
        "itube.auth.cognito.domain=https://itube-test.auth.eu-west-2.amazoncognito.com",
        "itube.auth.cognito.redirect-uri=https://itube.test/api/auth/oauth/cognito/callback",
        "itube.auth.cognito.post-login-redirect=https://itube.test/",
        "itube.auth.cognito.error-redirect=https://itube.test/login"})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class CognitoSignInTests {
    @Autowired private MockMvc mvc;
    @Autowired private AccessTokens tokens;
    @MockitoBean private CognitoClient cognito;
    @MockitoBean private CatalogClient catalog;
    @MockitoBean private AccountEvents accountEvents;

    @BeforeEach
    void setup() {
        when(cognito.authorizationUrl(anyString(), anyString(), anyString())).thenAnswer(call -> UriComponentsBuilder
                .fromUriString("https://itube-test.auth.eu-west-2.amazoncognito.com/oauth2/authorize")
                .queryParam("state", call.<String>getArgument(0))
                .queryParam("nonce", call.<String>getArgument(1))
                .queryParam("code_challenge", call.<String>getArgument(2))
                .build().toUri());
        when(catalog.ensureChannel(anyLong(), anyString())).thenAnswer(call ->
                new ChannelDto(9000L + call.<Long>getArgument(0), call.getArgument(0), call.getArgument(1)));
        when(catalog.channelsOwnedBy(any())).thenReturn(List.of());
    }

    private record Started(Cookie stateCookie, Map<String, String> query) {
    }

    private Started start() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/auth/oauth/cognito/authorize"))
                .andExpect(status().isFound()).andReturn().getResponse();
        Cookie stateCookie = response.getCookie("ITUBE_OAUTH");
        assertThat(stateCookie).isNotNull();
        assertThat(stateCookie.isHttpOnly()).isTrue();
        assertThat(stateCookie.getPath()).isEqualTo("/api/auth/oauth/cognito");
        URI location = URI.create(response.getRedirectedUrl());
        Map<String, String> query = UriComponentsBuilder.fromUri(location).build().getQueryParams().toSingleValueMap();
        return new Started(stateCookie, query);
    }

    @Test
    void theLoginPageOffersCognitoWhenConfigured() throws Exception {
        mvc.perform(get("/api/auth/providers")).andExpect(jsonPath("$.cognito").value(true));
    }

    @Test
    void aSuccessfulCallbackSignsTheUserInAndRedirectsToTheSite() throws Exception {
        Started started = start();
        String subject = UUID.randomUUID().toString();
        String email = "cognito-" + subject + "@example.com";
        when(cognito.exchangeCode(eq("the-code"), anyString())).thenReturn("id-token");
        when(cognito.verifyIdToken("id-token", started.query().get("nonce"))).thenReturn(new JWTClaimsSet.Builder()
                .subject(subject).claim("email", email).claim("email_verified", true)
                .claim("given_name", "Grace").claim("family_name", "Hopper").build());

        MockHttpServletResponse response = mvc.perform(get("/api/auth/oauth/cognito/callback")
                        .param("code", "the-code").param("state", started.query().get("state"))
                        .cookie(started.stateCookie()))
                .andExpect(status().isFound()).andReturn().getResponse();

        assertThat(response.getRedirectedUrl()).isEqualTo("https://itube.test/");
        String accessToken = response.getCookie(AccessTokens.COOKIE).getValue();
        assertThat(tokens.verify(accessToken).orElseThrow().displayName()).isEqualTo("Grace Hopper");
        assertThat(response.getCookie("ITUBE_RT")).isNotNull();
        assertThat(response.getCookie("ITUBE_OAUTH").getMaxAge()).isZero();
        mvc.perform(get("/api/auth/me").cookie(new Cookie(AccessTokens.COOKIE, accessToken)))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void theCodeVerifierSentToCognitoMatchesTheChallenge() throws Exception {
        Started started = start();
        when(cognito.exchangeCode(eq("code"), anyString())).thenReturn("id-token");
        when(cognito.verifyIdToken(anyString(), anyString())).thenReturn(new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString()).build());
        mvc.perform(get("/api/auth/oauth/cognito/callback").param("code", "code")
                .param("state", started.query().get("state")).cookie(started.stateCookie()));

        ArgumentCaptor<String> verifier = ArgumentCaptor.forClass(String.class);
        verify(cognito).exchangeCode(eq("code"), verifier.capture());
        assertThat(verifier.getValue()).hasSizeBetween(43, 128);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getValue().getBytes(StandardCharsets.US_ASCII)));
        assertThat(challenge).isEqualTo(started.query().get("code_challenge"));
    }

    @Test
    void aCallbackWithTheWrongStateIsRefused() throws Exception {
        Started started = start();
        mvc.perform(get("/api/auth/oauth/cognito/callback").param("code", "code").param("state", "forged")
                        .cookie(started.stateCookie()))
                .andExpect(status().isFound())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .isEqualTo("https://itube.test/login?error=cognito-state"));
        // Without the cookie (another browser, or a link someone sent): refused too
        mvc.perform(get("/api/auth/oauth/cognito/callback").param("code", "code")
                        .param("state", started.query().get("state")))
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).endsWith("error=cognito-state"));
        verify(cognito, never()).exchangeCode(anyString(), anyString());
    }

    @Test
    void cancellingOnTheCognitoPageReturnsToLogin() throws Exception {
        mvc.perform(get("/api/auth/oauth/cognito/callback").param("error", "access_denied"))
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .isEqualTo("https://itube.test/login?error=cognito-cancelled"));
    }

    @Test
    void unverifiedEmailsAreRefused() throws Exception {
        Started started = start();
        when(cognito.exchangeCode(anyString(), anyString())).thenReturn("id-token");
        when(cognito.verifyIdToken(anyString(), anyString())).thenReturn(new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString()).claim("email", "x@example.com").claim("email_verified", "false")
                .build());
        mvc.perform(get("/api/auth/oauth/cognito/callback").param("code", "c")
                        .param("state", started.query().get("state")).cookie(started.stateCookie()))
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).endsWith("error=cognito-unverified"))
                .andExpect(result -> assertThat(result.getResponse().getCookie(AccessTokens.COOKIE)).isNull());
    }
}
