package ITubeBackend.bdd;

import ITubeBackend.common.client.IdentityClient;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.support.FakeCatalog;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import ITubeBackend.security.AccessTokens;
import ITubeBackend.security.SignedInUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

public class EngagementSteps {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private FakeCatalog catalog;
    @Autowired private IdentityClient identity;
    @Autowired private AccessTokens tokens;

    private final Map<String, Long> videos = new HashMap<>();
    private final Map<String, RequestPostProcessor> viewers = new HashMap<>();
    private MvcResult last;

    @Before
    public void catalogIsUp() {
        catalog.setDown(false);
    }

    @Given("a public video {string} by creator {long}")
    public void aPublicVideo(String title, long creator) {
        videos.put(title, catalog.add(creator, title, VideoVisibility.PUBLIC).getVideoId());
    }

    @Given("a private video {string} by creator {long}")
    public void aPrivateVideo(String title, long creator) {
        videos.put(title, catalog.add(creator, title, VideoVisibility.PRIVATE).getVideoId());
    }

    @Given("a signed-in viewer {string}")
    public void aSignedInViewer(String name) {
        // Each scenario gets fresh user ids: the in-memory stores live for the whole run
        long userId = ThreadLocalRandom.current().nextLong(1_000_000, 900_000_000);
        // A real signed access token, carrying the display name like the one identity-service issues
        String token = tokens.issue(new SignedInUser(userId, null, name));
        viewers.put(name, request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        });
    }

    @Given("catalog-service is down")
    public void catalogIsDown() {
        catalog.setDown(true);
    }

    @Given("{string} has watched {string}")
    @When("{string} watches {string}")
    public void watches(String viewer, String title) throws Exception {
        last = mvc.perform(post("/api/history").with(viewers.get(viewer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"videoId\":" + videos.get(title) + "}")).andReturn();
        assertThat(last.getResponse().getStatus()).isEqualTo(202);
    }

    @Given("{string} has liked {string}")
    @When("{string} likes {string}")
    public void likes(String viewer, String title) throws Exception {
        last = mvc.perform(put("/api/liked-videos/" + videos.get(title)).with(viewers.get(viewer))).andReturn();
    }

    @When("{string} unlikes {string}")
    public void unlikes(String viewer, String title) throws Exception {
        last = mvc.perform(delete("/api/liked-videos/" + videos.get(title)).with(viewers.get(viewer))).andReturn();
    }

    @When("{string} comments {string} on {string}")
    public void comments(String viewer, String text, String title) throws Exception {
        last = mvc.perform(post("/api/videos/" + videos.get(title) + "/comments").with(viewers.get(viewer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + text + "\"}")).andReturn();
        assertThat(last.getResponse().getStatus()).isEqualTo(201);
    }

    @Then("the history of {string} lists {string} then {string}")
    public void historyLists(String viewer, String first, String second) throws Exception {
        JsonNode history = getJson("/api/history", viewers.get(viewer));
        assertThat(history.get(0).get("videoId").asLong()).isEqualTo(videos.get(first));
        assertThat(history.get(1).get("videoId").asLong()).isEqualTo(videos.get(second));
        assertThat(history.get(1).get("video").get("title").asString()).isEqualTo(second);
    }

    @Then("the {string} entry shows no video details because the video is private")
    public void noDetailsForPrivate(String title) throws Exception {
        for (JsonNode entry : getJson("/api/history", viewers.values().iterator().next())) {
            if (entry.get("videoId").asLong() == videos.get(title)) assertThat(entry.get("video").isNull()).isTrue();
        }
    }

    @Then("the history of {string} still has {int} entries, without video details")
    public void historyWithoutDetails(String viewer, int count) throws Exception {
        JsonNode history = getJson("/api/history", viewers.get(viewer));
        assertThat(history.size()).isEqualTo(count);
        history.forEach(entry -> assertThat(entry.get("video").isNull()).isTrue());
    }

    @Then("{string} has {int} liked video(s)")
    public void hasLikedVideos(String viewer, int count) throws Exception {
        assertThat(getJson("/api/liked-videos", viewers.get(viewer)).size()).isEqualTo(count);
    }

    @Then("the request is answered with status {int}")
    public void answeredWith(int status) {
        assertThat(last.getResponse().getStatus()).isEqualTo(status);
    }

    @Then("the comments on {string} show {string} by {string}")
    public void commentsShow(String title, String text, String author) throws Exception {
        List<String> seen = new ArrayList<>();
        getJson("/api/videos/" + videos.get(title) + "/comments", null)
                .forEach(c -> seen.add(c.get("content").asString() + " by " + c.get("authorName").asString()));
        assertThat(seen).contains(text + " by " + author);
    }

    @Then("identity-service was not asked who {string} is")
    public void identityNotAsked(String viewer) {
        verify(identity, never()).account(any());
    }

    private JsonNode getJson(String path, RequestPostProcessor session) throws Exception {
        var request = get(path);
        if (session != null) request.with(session);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as("GET " + path).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }
}
