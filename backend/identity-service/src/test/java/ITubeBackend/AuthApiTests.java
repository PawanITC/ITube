package ITubeBackend;

import ITubeBackend.controller.AuthController;
import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.controller.DevSessionController;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.service.account.AccountEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@Transactional
class AuthApiTests {
    @Autowired private AuthController authController;
    @Autowired private DevSessionController devSessionController;
    // catalog-service is another process: stand in for it with an in-memory "channel per user"
    @MockitoBean private CatalogClient catalog;
    @MockitoBean private AccountEvents accountEvents;
    private final ConcurrentHashMap<Long, ChannelDto> channels = new ConcurrentHashMap<>();
    private final AtomicLong channelIds = new AtomicLong(100);
    private MockMvc mvc;
    private String email;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(authController, devSessionController).build();
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

    @Test
    void registerSignsInAndCreatesAChannel() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("correct horse")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.firstname").value("Ada"))
                .andExpect(jsonPath("$.channelName").value("Ada's Lab"))
                .andExpect(jsonPath("$.channelId").isNumber())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);

        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()));
        mvc.perform(get("/api/session/current").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.channelId").isNumber());
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("correct horse"))).andExpect(status().isCreated());
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
    void loginChecksThePasswordAndLogoutEndsTheSession() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("correct horse"))).andExpect(status().isCreated());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(email, "wrong password"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("nobody-" + email, "correct horse"))).andExpect(status().isUnauthorized());

        MockHttpSession before = new MockHttpSession();
        String idBefore = before.getId();
        MvcResult result = mvc.perform(post("/api/auth/login").session(before).contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("  " + email.toUpperCase() + " ", "correct horse")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelName").value("Ada's Lab"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotEquals(idBefore, session.getId());

        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(new MockHttpSession())).andExpect(status().isUnauthorized());
    }
}
