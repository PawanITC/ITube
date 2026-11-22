package ITubeBackend.bdd;

import ITubeBackend.cache.CatalogCaches;
import ITubeBackend.common.client.DownstreamUnavailableException;
import ITubeBackend.common.client.PaymentsClient;
import ITubeBackend.dto.entitlement.PlaybackDecision;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.security.AccessTokens;
import ITubeBackend.security.SignedInUser;
import ITubeBackend.sharding.ShardContext;
import ITubeBackend.sharding.ShardIds;
import ITubeBackend.sharding.ShardMap;
import ITubeBackend.sharding.Shards;
import io.cucumber.java.Before;
import io.cucumber.java.en.But;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Step definitions for the catalog features. A new instance per scenario, so the fields are per scenario. */
public class CatalogSteps {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ShardMap shardMap;
    @Autowired private AccessTokens tokens;
    @Autowired private Shards shards;
    @Autowired private VideoRepository videoRepository;
    @Autowired private CatalogCaches caches;
    @Autowired private PaymentsClient payments;

    private record Person(long userId, Long channelId, RequestPostProcessor session) {
    }

    private final Map<String, Person> people = new HashMap<>();
    private final Map<String, Long> videos = new HashMap<>();
    // Names must be unique across scenarios: the databases live for the whole test run
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private JsonNode lastSearch;

    @Before
    public void paymentsAnswersNormally() {
        doReturn(PlaybackDecision.deny("MEMBERSHIP_REQUIRED")).when(payments).decide(any(), any());
    }

    // ------------------------------------------------------------------ given

    @Given("the catalog runs on {int} database shards")
    public void theCatalogRunsOnShards(int count) {
        assertThat(shardMap.physicalCount()).isEqualTo(count);
    }

    @Given("a creator {string} whose data lives on shard {int}")
    public void aCreatorOnShard(String name, int shard) throws Exception {
        long userId = userIdLandingOn(shard);
        MvcResult result = mvc.perform(post("/internal/channels").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + userId + ",\"channelName\":\"" + name + " " + run + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        long channelId = json.readTree(result.getResponse().getContentAsString()).get("channelId").asLong();
        people.put(name, new Person(userId, channelId, session(userId, channelId, name)));
    }

    @Given("a viewer {string}")
    public void aViewer(String name) {
        long userId = ThreadLocalRandom.current().nextLong(1_000_000, 9_000_000);
        people.put(name, new Person(userId, null, session(userId, null, name)));
    }

    @Given("payments-service is down")
    public void paymentsIsDown() {
        doThrow(new DownstreamUnavailableException("payments", null)).when(payments).decide(any(), any());
    }

    @Given("the feed cache has expired")
    public void theFeedCacheHasExpired() {
        // Stands in for the 10 s TTL running out
        caches.feed.invalidateAll();
    }

    // ------------------------------------------------------------------ when

    @When("{string} uploads a public video {string}")
    public void uploadsAPublicVideo(String creator, String title) throws Exception {
        upload(creator, title, "PUBLIC", null);
    }

    @When("{string} uploads a members-only video {string}")
    public void uploadsAMembersOnlyVideo(String creator, String title) throws Exception {
        upload(creator, title, "MEMBERS_ONLY", "1");
    }

    @When("{string} makes {string} private")
    public void makesPrivate(String creator, String title) throws Exception {
        int status = mvc.perform(patch("/api/videos/" + videos.get(title) + "/visibility")
                        .with(people.get(creator).session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PRIVATE\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(204);
    }

    @When("a visitor searches channels for {string}")
    public void aVisitorSearches(String query) throws Exception {
        lastSearch = getJson("/api/channels/search?q=" + query + " ", null);
    }

    @When("{string} subscribes to the channels of {string} and {string}")
    public void subscribesTo(String viewer, String first, String second) throws Exception {
        for (String creator : List.of(first, second)) {
            int status = mvc.perform(post("/api/channels/" + people.get(creator).channelId() + "/subscriptions")
                    .with(people.get(viewer).session())).andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(201);
        }
    }

    @When("a visitor opens the home feed")
    public void aVisitorOpensTheHomeFeed() throws Exception {
        feedTitles();
    }

    // ------------------------------------------------------------------ then

    @Then("the video {string} is stored only on shard {int}")
    public void theVideoIsStoredOnlyOn(String title, int shard) {
        Long videoId = videos.get(title);
        assertThat(shards.physicalOf(videoId)).hasValue(shard);
        for (int other : shardMap.physicalShards()) {
            boolean present = ShardContext.on(other, () -> videoRepository.existsById(videoId));
            assertThat(present).as("video row on shard " + other).isEqualTo(other == shard);
        }
    }

    @Then("the video {string} carries the same shard in its id as the channel of {string}")
    public void sameShardInIds(String title, String creator) {
        assertThat(ShardIds.logicalShardOf(videos.get(title)))
                .isEqualTo(ShardIds.logicalShardOf(people.get(creator).channelId()));
    }

    @Then("the home feed shows {string} before {string}")
    public void theFeedShowsBefore(String newer, String older) throws Exception {
        List<String> titles = feedTitles();
        assertThat(titles).contains(newer, older);
        assertThat(titles.indexOf(newer)).isLessThan(titles.indexOf(older));
    }

    @Then("the home feed shows {string}")
    public void theFeedShows(String title) throws Exception {
        assertThat(feedTitles()).contains(title);
    }

    @Then("the home feed does not show {string}")
    public void theFeedDoesNotShow(String title) throws Exception {
        assertThat(feedTitles()).doesNotContain(title);
    }

    @Then("the results include the channels of {string} and {string}")
    public void resultsInclude(String first, String second) {
        List<Long> ids = new ArrayList<>();
        lastSearch.forEach(node -> ids.add(node.get("channelId").asLong()));
        assertThat(ids).contains(people.get(first).channelId(), people.get(second).channelId());
    }

    @Then("the subscriptions of {string} list the channels of {string} and {string}")
    public void subscriptionsList(String viewer, String first, String second) throws Exception {
        List<Long> ids = new ArrayList<>();
        getJson("/api/channels/subscriptions", people.get(viewer).session())
                .forEach(node -> ids.add(node.get("channelId").asLong()));
        assertThat(ids).containsExactlyInAnyOrder(people.get(first).channelId(), people.get(second).channelId());
    }

    @Then("one internal summaries call returns both {string} and {string}")
    public void oneInternalCallReturnsBoth(String first, String second) throws Exception {
        String body = mvc.perform(post("/internal/videos/summaries").contentType(MediaType.APPLICATION_JSON)
                        .content("[" + videos.get(first) + "," + videos.get(second) + "]"))
                .andReturn().getResponse().getContentAsString();
        List<String> titles = new ArrayList<>();
        json.readTree(body).forEach(node -> titles.add(node.get("summary").get("title").asString()));
        assertThat(titles).containsExactlyInAnyOrder(first + " " + run, second + " " + run);
    }

    @Then("an id created before sharding, such as {long}, belongs to shard {int}")
    public void legacyIdsBelongToShard(long id, int shard) {
        assertThat(ShardIds.logicalShardOf(id)).isZero();
        assertThat(shards.physicalOf(id)).hasValue(shard);
    }

    @Then("the largest possible id is still exact in a browser")
    public void largestIdIsJsSafe() {
        long largest = ShardIds.compose(ShardIds.LOGICAL_SHARDS - 1, ShardIds.MAX_SEQUENCE);
        assertThat(largest).isLessThan(1L << 53); // Number.MAX_SAFE_INTEGER + 1
    }

    @Then("{string} gets {string} when asking to play {string}")
    public void getsNotFound(String viewer, String expected, String title) throws Exception {
        assertThat(expected).isEqualTo("not found");
        assertThat(playbackStatus(viewer, title)).isEqualTo(404);
    }

    @Then("{string} may play {string}")
    public void mayPlay(String viewer, String title) throws Exception {
        MvcResult result = mvc.perform(get("/api/videos/" + videos.get(title) + "/playback-authorisation")
                .with(people.get(viewer).session())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("allowed").asBoolean()).isTrue();
    }

    @But("{string} may still play {string}")
    public void mayStillPlay(String viewer, String title) throws Exception {
        mayPlay(viewer, title);
    }

    @Then("{string} is told to try again later when asking to play {string}")
    public void toldToTryAgain(String viewer, String title) throws Exception {
        assertThat(playbackStatus(viewer, title)).isEqualTo(503);
    }

    // ------------------------------------------------------------------ helpers

    private long userIdLandingOn(int physicalShard) {
        long candidate = ThreadLocalRandom.current().nextLong(10_000_000, 900_000_000);
        while (shardMap.physicalOf(ShardIds.logicalShardForNewOwner(candidate)) != physicalShard) candidate++;
        return candidate;
    }

    /** Requests signed in as this person: a real access token, verified by the authentication filter. */
    private RequestPostProcessor session(long userId, Long channelId, String name) {
        String token = tokens.issue(new SignedInUser(userId, channelId, name));
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    private void upload(String creator, String title, String visibility, String tier) throws Exception {
        var request = multipart("/api/videos")
                .file(new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'}))
                .param("title", title + " " + run)
                .param("duration", "00:01:00")
                .param("visibility", visibility)
                .with(people.get(creator).session());
        if (tier != null) request.param("requiredTierLevel", tier);
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(201);
        for (JsonNode video : getJson("/api/videos/mine", people.get(creator).session())) {
            if (video.get("title").asString().equals(title + " " + run)) videos.put(title, video.get("videoId").asLong());
        }
        assertThat(videos).containsKey(title);
    }

    private int playbackStatus(String viewer, String title) throws Exception {
        return mvc.perform(get("/api/videos/" + videos.get(title) + "/playback-authorisation")
                .with(people.get(viewer).session())).andReturn().getResponse().getStatus();
    }

    private List<String> feedTitles() throws Exception {
        List<String> titles = new ArrayList<>();
        getJson("/api/videos?limit=200", null).forEach(node -> titles.add(
                node.get("title").asString().replace(" " + run, "")));
        return titles;
    }

    private JsonNode getJson(String path, RequestPostProcessor session) throws Exception {
        var request = get(path);
        if (session != null) request.with(session);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as("GET " + path).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }
}
