package ITubeBackend.e2e;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class JourneySteps {
    private final Map<String, Browser> browsers = new HashMap<>();
    private final Map<String, JsonNode> accounts = new HashMap<>();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private long videoId;
    private String videoTitle;
    private final List<Integer> rateLimitStatuses = new ArrayList<>();

    @Given("{string} registers a new account")
    public void registers(String name) {
        Browser browser = new Browser();
        Browser.Response response = browser.postJson("/api/auth/register", """
                {"firstname":"%s","lastname":"E2E","email":"%s-%s@e2e.itube.test","password":"e2e-password-123",
                 "channelName":"%s %s"}""".formatted(name, name.toLowerCase(), run, name, run));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        browsers.put(name, browser);
        accounts.put(name, response.json());
        assertThat(accounts.get(name).get("channelId").isNumber()).as("channel created by catalog-service").isTrue();
    }

    @When("{string} uploads a public video titled {string} with a resumable upload")
    public void uploadsResumably(String creator, String title) {
        Browser browser = browsers.get(creator);
        videoTitle = title + " " + run;
        byte[] file = new byte[3 * 1024 * 1024];
        new java.util.Random(7).nextBytes(file);
        Browser.Response started = browser.postJson("/api/uploads", """
                {"fileName":"e2e.mp4","fileSize":%d,"lastModified":1700000000000,"contentType":"video/mp4",
                 "title":"%s","duration":"00:00:05","visibility":"PUBLIC"}""".formatted(file.length, videoTitle));
        assertThat(started.status()).as(started.body()).isEqualTo(200);
        JsonNode session = started.json();
        String uploadId = session.get("uploadId").asString();
        int partCount = session.get("partCount").asInt();
        long partSize = session.get("partSize").asLong();

        List<String> parts = new ArrayList<>();
        for (int i = 1; i <= partCount; i++) parts.add(Integer.toString(i));
        Browser.Response urls = browser.postJson("/api/uploads/" + uploadId + "/part-urls",
                "{\"partNumbers\":[" + String.join(",", parts) + "]}");
        assertThat(urls.status()).as(urls.body()).isEqualTo(200);
        for (JsonNode part : urls.json()) {
            int number = part.get("partNumber").asInt();
            int from = (int) ((number - 1) * partSize);
            int to = (int) Math.min(file.length, from + partSize);
            // Usually a pre-signed URL: the bytes go straight to object storage, not through ITube
            Browser.Response put = browser.putBytes(part.get("url").asString(), java.util.Arrays.copyOfRange(file, from, to));
            assertThat(put.status()).as("part " + number + ": " + put.body()).isBetween(200, 299);
        }
        Browser.Response completed = browser.postJson("/api/uploads/" + uploadId + "/complete", "");
        assertThat(completed.status()).as(completed.body()).isEqualTo(201);
        videoId = completed.json().get("videoId").asLong();
    }

    @Then("the video appears on the home feed within {int} seconds")
    public void appearsOnFeed(int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        List<Long> ids = List.of();
        while (System.currentTimeMillis() < deadline) {
            // The feed is cached for up to 10 s per catalog pod (and at the CDN)
            ids = new ArrayList<>();
            for (JsonNode video : new Browser().get("/api/videos?limit=200").json()) ids.add(video.get("videoId").asLong());
            if (ids.contains(videoId)) return;
            Thread.sleep(1000);
        }
        assertThat(ids).contains(videoId);
    }

    @When("{string} watches the video")
    public void watches(String viewer) {
        Browser.Response auth = browsers.get(viewer).get("/api/videos/" + videoId + "/playback-authorisation");
        assertThat(auth.status()).as(auth.body()).isEqualTo(200);
        assertThat(auth.json().get("allowed").asBoolean()).isTrue();
        Browser.Response file = browsers.get(viewer).get("/api/videos/" + videoId + "/file");
        // A redirect to object storage / CDN (or the bytes, when storage can't pre-sign)
        assertThat(file.status()).isIn(200, 206, 302);
        Browser.Response history = browsers.get(viewer).postJson("/api/history", "{\"videoId\":" + videoId + "}");
        assertThat(history.status()).as(history.body()).isEqualTo(201);
    }

    @When("{string} likes the video")
    public void likes(String viewer) {
        assertThat(browsers.get(viewer).put("/api/liked-videos/" + videoId).status()).isEqualTo(204);
    }

    @When("{string} comments {string} on the video")
    public void comments(String viewer, String text) {
        Browser.Response response = browsers.get(viewer).postJson("/api/videos/" + videoId + "/comments",
                "{\"content\":\"" + text + "\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(201);
    }

    @Then("the history of {string} shows the video with its title")
    public void historyShows(String viewer) {
        JsonNode history = browsers.get(viewer).get("/api/history").json();
        assertThat(history.get(0).get("videoId").asLong()).isEqualTo(videoId);
        assertThat(history.get(0).get("video").get("title").asString()).isEqualTo(videoTitle);
    }

    @Then("{string} sees the video among their liked videos")
    public void seesLiked(String viewer) {
        List<Long> liked = new ArrayList<>();
        browsers.get(viewer).get("/api/liked-videos").json().forEach(like -> liked.add(like.get("video").get("videoId").asLong()));
        assertThat(liked).contains(videoId);
    }

    @Then("the comments on the video show {string} by {string}")
    public void commentsShow(String text, String author) {
        List<String> seen = new ArrayList<>();
        new Browser().get("/api/videos/" + videoId + "/comments").json()
                .forEach(c -> seen.add(c.get("content").asString() + " by " + c.get("authorName").asString()));
        assertThat(seen).contains(text + " by " + author + " E2E");
    }

    @When("{string} subscribes to the channel of {string}")
    public void subscribes(String viewer, String creator) {
        long channelId = accounts.get(creator).get("channelId").asLong();
        Browser.Response response = browsers.get(viewer).postJson("/api/channels/" + channelId + "/subscriptions", "");
        assertThat(response.status()).as(response.body()).isEqualTo(201);
    }

    @Then("{string} sees the channel of {string} in their subscriptions")
    public void seesSubscription(String viewer, String creator) {
        List<Long> ids = new ArrayList<>();
        browsers.get(viewer).get("/api/channels/subscriptions").json().forEach(c -> ids.add(c.get("channelId").asLong()));
        assertThat(ids).contains(accounts.get(creator).get("channelId").asLong());
    }

    @When("{string} makes the video private")
    public void makesPrivate(String creator) {
        Browser.Response response = browsers.get(creator).patchJson("/api/videos/" + videoId + "/visibility",
                "{\"visibility\":\"PRIVATE\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(204);
    }

    @Then("{string} can no longer play the video")
    public void cannotPlay(String viewer) {
        assertThat(browsers.get(viewer).get("/api/videos/" + videoId + "/playback-authorisation").status()).isEqualTo(404);
    }

    @Then("{string} is signed in on identity, catalog, engagement and payments")
    public void signedInEverywhere(String name) {
        Browser browser = browsers.get(name);
        assertThat(browser.get("/api/auth/me").status()).as("identity").isEqualTo(200);
        assertThat(browser.get("/api/videos/mine").status()).as("catalog").isEqualTo(200);
        assertThat(browser.get("/api/history").status()).as("engagement").isEqualTo(200);
        assertThat(browser.get("/api/memberships/me").status()).as("payments").isEqualTo(200);
        // And a stranger without the cookie is not
        assertThat(new Browser().get("/api/history").status()).isEqualTo(401);
    }

    @Then("a request to {string} through the gateway is answered with {int}")
    public void requestAnsweredWith(String path, int status) {
        assertThat(new Browser().get(path).status()).isEqualTo(status);
    }

    @When("someone tries to sign in {int} times in a row with a wrong password")
    public void bruteForce(int attempts) {
        Browser attacker = new Browser();
        for (int i = 0; i < attempts; i++) {
            rateLimitStatuses.add(attacker.postJson("/api/auth/login",
                    "{\"email\":\"nobody@e2e.itube.test\",\"password\":\"guess-" + i + "\"}").status());
        }
    }

    @Then("some of those attempts are refused with 429 Too Many Requests")
    public void someRefused() {
        assertThat(rateLimitStatuses).contains(429);
        assertThat(rateLimitStatuses).contains(401);
    }
}
