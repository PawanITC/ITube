package ITubeBackend.bdd;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.client.DownstreamUnavailableException;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.security.AccessTokens;
import ITubeBackend.security.SignedInUser;
import ITubeBackend.service.account.AccountEvents;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

public class AccountSteps {
    private static final String PASSWORD = "correct horse battery";
    private static final Map<String, String> SURNAMES = Map.of("Ada", "Lovelace", "Grace", "Hopper", "Linus", "Torvalds",
            "Barbara", "Liskov", "Margaret", "Hamilton");
    private static final AtomicLong CHANNEL_IDS = new AtomicLong(5000);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private CatalogClient catalog;
    @Autowired private AccountEvents accountEvents;
    @Autowired private AccessTokens tokens;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private MvcResult last;
    private String lastEmail;
    private String lastChannelName;

    @Before
    public void catalogAnswersNormally() {
        doAnswer(call -> new ChannelDto(CHANNEL_IDS.incrementAndGet(), call.getArgument(0), call.getArgument(1)))
                .when(catalog).ensureChannel(anyLong(), anyString());
        doAnswer(call -> List.of(new ChannelDto(1L, call.getArgument(0), "Primary"))).when(catalog).channelsOwnedBy(any());
    }

    @Given("catalog-service is down")
    public void catalogIsDown() {
        DownstreamUnavailableException outage = new DownstreamUnavailableException("catalog", null);
        doThrow(outage).when(catalog).ensureChannel(anyLong(), anyString());
        doThrow(outage).when(catalog).channelsOwnedBy(any());
    }

    @Given("{string} has registered")
    public void hasRegistered(String name) throws Exception {
        register(name, name + "'s channel");
        assertThat(last.getResponse().getStatus()).isEqualTo(201);
    }

    @When("{string} registers with the channel name {string}")
    public void registers(String name, String channelName) throws Exception {
        register(name, channelName);
    }

    @When("someone registers again with the same email in capitals")
    public void registersAgainInCapitals() throws Exception {
        last = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstname":"Copy","lastname":"Cat","email":"%s","password":"%s"}
                """.formatted(lastEmail.toUpperCase(), PASSWORD))).andReturn();
    }

    @When("{string} signs in with a wrong password")
    public void signsInWrong(String name) throws Exception {
        signIn("wrong password!");
    }

    @When("{string} signs in with the right password")
    public void signsInRight(String name) throws Exception {
        signIn(PASSWORD);
    }

    @Then("the registration succeeds")
    public void registrationSucceeds() throws Exception {
        assertThat(last.getResponse().getStatus()).isEqualTo(201);
        assertThat(body().get("channelId").isNumber()).isTrue();
    }

    @Then("the access token holds the user's id, channel and display name {string}")
    public void accessTokenHolds(String displayName) throws Exception {
        Cookie cookie = last.getResponse().getCookie(AccessTokens.COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        SignedInUser user = tokens.verify(cookie.getValue()).orElseThrow();
        assertThat(user.userId()).isEqualTo(body().get("userId").asLong());
        assertThat(user.channelId()).isEqualTo(body().get("channelId").asLong());
        assertThat(user.displayName()).isEqualTo(displayName);
    }

    @Then("the catalog was asked to create the channel {string}")
    public void catalogWasAsked(String channelName) throws Exception {
        verify(catalog).ensureChannel(body().get("userId").asLong(), channelName);
        // The direct call worked, so no event is needed (it would race the call)
        verify(accountEvents, never()).accountCreated(any(), any());
    }

    @Then("that registration is refused as a conflict")
    public void refusedAsConflict() {
        assertThat(last.getResponse().getStatus()).isEqualTo(409);
    }

    @Then("signing in is refused")
    public void signingInRefused() {
        assertThat(last.getResponse().getStatus()).isEqualTo(401);
    }

    @Then("the registration succeeds without a channel for now")
    public void succeedsWithoutChannel() throws Exception {
        assertThat(last.getResponse().getStatus()).isEqualTo(201);
        assertThat(body().get("channelId").isNull()).isTrue();
    }

    @Then("an account-created event asks the catalog to create {string} later")
    public void eventPublished(String channelName) throws Exception {
        verify(accountEvents).accountCreated(eq(body().get("userId").asLong()), eq(channelName));
    }

    @Then("signing in succeeds without channel details")
    public void signInWithoutChannel() throws Exception {
        assertThat(last.getResponse().getStatus()).isEqualTo(200);
        assertThat(body().get("channelId").isNull()).isTrue();
    }

    private void register(String name, String channelName) throws Exception {
        lastEmail = name.toLowerCase() + "-" + run + "@example.com";
        lastChannelName = channelName;
        last = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstname":"%s","lastname":"%s","email":"%s","password":"%s","channelName":"%s"}
                """.formatted(name, SURNAMES.getOrDefault(name, "Tester"), lastEmail, PASSWORD, channelName))).andReturn();
    }

    private void signIn(String password) throws Exception {
        last = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(lastEmail, password))).andReturn();
    }

    private JsonNode body() throws Exception {
        MockHttpServletResponse response = last.getResponse();
        return json.readTree(response.getContentAsString());
    }
}
