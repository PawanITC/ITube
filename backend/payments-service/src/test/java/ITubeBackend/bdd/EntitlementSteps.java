package ITubeBackend.bdd;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.client.DownstreamUnavailableException;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.security.AccessTokens;
import ITubeBackend.security.SignedInUser;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

public class EntitlementSteps {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AccessTokens tokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogClient catalog;

    private final Map<Integer, Long> tierByLevel = new HashMap<>();
    private long channelId;
    private MvcResult last;

    @Before
    public void cleanAndCatalogIsUp() {
        // The database lives for the whole run; each scenario starts without memberships or tiers
        jdbc.update("DELETE FROM payments.channel_memberships");
        jdbc.update("DELETE FROM payments.channel_membership_tiers");
        doAnswer(call -> Optional.of(new ChannelDto(call.getArgument(0), 99L, "Creator channel")))
                .when(catalog).channel(anyLong());
    }

    @Given("channel {long} has a membership tier at level {int} and one at level {int}")
    public void channelHasTiers(long channel, int first, int second) {
        channelId = channel;
        for (int level : new int[]{first, second}) {
            jdbc.update("INSERT INTO payments.channel_membership_tiers (channel_id, name, level, price_minor, currency, active, "
                    + "created_at, updated_at) VALUES (?, ?, ?, 299, 'gbp', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    channel, "Level " + level, level);
            tierByLevel.put(level, jdbc.queryForObject("SELECT tier_id FROM payments.channel_membership_tiers "
                    + "WHERE channel_id = ? AND level = ?", Long.class, channel, level));
        }
    }

    @Given("viewer {long} is a {string} member at level {int} whose period ends in {int} days")
    public void viewerIsMember(long viewer, String status, int level, int days) {
        jdbc.update("INSERT INTO payments.channel_memberships (user_id, channel_id, tier_id, stripe_subscription_id, "
                        + "stripe_customer_id, status, cancel_at_period_end, current_period_end, entitled, last_synced_at, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'cus_test', ?, FALSE, ?, TRUE, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                viewer, channelId, tierByLevel.get(level), "sub_" + UUID.randomUUID(), status,
                Timestamp.from(Instant.now().plus(days, ChronoUnit.DAYS)));
    }

    @Given("catalog-service is down")
    public void catalogIsDown() {
        doThrow(new DownstreamUnavailableException("catalog", null)).when(catalog).channel(anyLong());
    }

    @When("the catalog asks whether viewer {long} may play a members-only video of channel {long} that needs level {int}")
    public void catalogAsks(long viewer, long channel, int level) throws Exception {
        last = mvc.perform(post("/internal/entitlements/decide").contentType(MediaType.APPLICATION_JSON).content("""
                {"userId":%d,"video":{"videoId":1,"channelId":%d,"ownerUserId":99,"visibility":"MEMBERS_ONLY",
                 "requiredTierLevel":%d,"duration":"00:10:00"}}
                """.formatted(viewer, channel, level))).andReturn();
        assertThat(last.getResponse().getStatus()).isEqualTo(200);
    }

    @When("viewer {long} opens their memberships page")
    public void opensMembershipsPage(long viewer) throws Exception {
        last = mvc.perform(get("/api/memberships/me")
                .header("Authorization", "Bearer " + tokens.issue(new SignedInUser(viewer, null, null)))).andReturn();
    }

    @Then("the answer is {string}")
    public void theAnswerIs(String expected) throws Exception {
        JsonNode decision = json.readTree(last.getResponse().getContentAsString());
        if (expected.equals("allowed")) {
            assertThat(decision.get("allowed").asBoolean()).isTrue();
        } else {
            assertThat(decision.get("allowed").asBoolean()).isFalse();
            assertThat(decision.get("reason").asString()).isEqualTo(expected);
        }
    }

    @Then("the page lists {int} membership with the placeholder name {string}")
    public void pageLists(int count, String name) throws Exception {
        assertThat(last.getResponse().getStatus()).isEqualTo(200);
        JsonNode memberships = json.readTree(last.getResponse().getContentAsString());
        assertThat(memberships.size()).isEqualTo(count);
        assertThat(memberships.get(0).get("channelName").asString()).isEqualTo(name);
    }
}
