package ITubeBackend;

import ITubeBackend.common.client.RecommendationCatalogClient;
import ITubeBackend.engagement.*;
import ITubeBackend.engagement.commands.*;
import ITubeBackend.engagement.commands.EngagementCommand.Action;
import ITubeBackend.engagement.scylla.ScyllaProperties;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.security.*;
import ITubeBackend.support.FakeCatalog;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt in: docker compose up -d kafka scylla; mvnw -pl engagement-service -am test -Ditube.test.kafka=true */
@EnabledIfSystemProperty(named = "itube.test.kafka", matches = "true")
@SpringBootTest(properties = {
        "itube.engagement.store=scylla", "itube.engagement.kafka.transport=kafka",
        "spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true",
        "itube.engagement.kafka.concurrency=1", "itube.topics.partitions=1", "itube.migration.legacy-data=false"
})
@ActiveProfiles("h2") // Only disables telemetry/migration; explicit properties above use REAL Kafka and ScyllaDB.
@AutoConfigureMockMvc
@Import(FakeCatalog.Config.class)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngagementKafkaScyllaTests {
    static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");
    static final String TOPIC = "itube.test.engagement." + SUFFIX;
    static final String KEYSPACE = "itube_kafka_test_" + SUFFIX.substring(0, 20);
    @DynamicPropertySource static void settings(DynamicPropertyRegistry properties) {
        properties.add("itube.scylla.keyspace", () -> KEYSPACE);
        properties.add("itube.engagement.kafka.topic", () -> TOPIC);
        properties.add("itube.topics.video-deleted", () -> TOPIC + ".deleted");
    }
    @Autowired MockMvc mvc;
    @Autowired AccessTokens tokens;
    @Autowired FakeCatalog catalog;
    @Autowired OperationStore operations;
    @Autowired WatchHistoryStore history;
    @Autowired LikeStore likes;
    @Autowired SavedVideoStore saved;
    @Autowired PlaylistStore playlists;
    @Autowired MutationPlanner planner;
    @Autowired ProjectionWriter writer;
    @Autowired CqlSession session;
    @Autowired ObjectMapper json;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry registry;
    @MockitoBean RecommendationCatalogClient recommendations;
    long user, video;
    String token;

    @BeforeEach void setup() {
        user = Math.abs(UUID.randomUUID().getLeastSignificantBits() / 2) + 1;
        token = tokens.issue(new SignedInUser(user, null, null));
        video = catalog.add(2L, "Real Kafka and Scylla", VideoVisibility.PUBLIC).getVideoId();
        when(recommendations.candidates(any())).thenReturn(List.of());
        when(recommendations.eligibleVideos(any())).thenReturn(List.of());
    }
    JsonNode submit(String method, String path, String body, UUID key) throws Exception {
        var request = switch (method) {
            case "POST" -> post(path); case "PUT" -> put(path); case "PATCH" -> patch(path); default -> delete(path);
        };
        request.header("Authorization", "Bearer " + token).header("Idempotency-Key", key.toString());
        if (body != null) request.contentType("application/json").content(body);
        return json.readTree(mvc.perform(request).andExpect(status().isAccepted()).andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString());
    }
    JsonNode complete(String method, String path, String body) throws Exception {
        UUID key = UUID.randomUUID(); submit(method, path, body, key); return await(key);
    }
    JsonNode await(UUID operationId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            JsonNode receipt = json.readTree(mvc.perform(get("/api/engagement/operations/" + operationId)
                    .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if (!receipt.get("state").asString().equals("PENDING")) {
                assertThat(receipt.get("state").asString()).as(receipt.toString()).isEqualTo("SUCCEEDED");
                return receipt.get("result");
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for operation " + operationId);
    }

    @Test void brokerAcceptancePrecedesDatabaseWriteAndDuplicateReplayDoesNotUndoClear() throws Exception {
        registry.stop();
        UUID key = UUID.randomUUID();
        try {
            submit("POST", "/api/history", "{\"videoId\":" + video + "}", key);
            assertThat(history.page(user, 0, 20)).isEmpty();
        } finally { registry.start(); }
        await(key);
        var original = operations.find(user, key).orElseThrow().command();
        assertThat(history.page(user, 0, 20)).hasSize(1);
        submit("POST", "/api/history", "{\"videoId\":" + video + "}", key);
        assertThat(history.page(user, 0, 20)).hasSize(1);
        complete("DELETE", "/api/history", null);
        kafka.send(TOPIC, Long.toString(user), json.writeValueAsString(original)).get(10, TimeUnit.SECONDS);
        complete("PUT", "/api/saved-videos/" + video, null); // Same-user barrier after replay.
        assertThat(history.page(user, 0, 20)).isEmpty();
        assertThat(saved.isSaved(user, video)).isTrue();
    }

    @Test void likesSavesAndPlaylistsPersistThroughRealConsumers() throws Exception {
        complete("PUT", "/api/liked-videos/" + video, null);
        assertThat(likes.isLiked(user, video)).isTrue();
        complete("DELETE", "/api/liked-videos/" + video, null);
        assertThat(likes.isLiked(user, video)).isFalse();
        complete("PUT", "/api/saved-videos/" + video, null);
        complete("DELETE", "/api/saved-videos", null);
        assertThat(saved.isSaved(user, video)).isFalse();
        var playlist = complete("POST", "/api/playlists", "{\"title\":\"Kafka playlist\"}");
        UUID id = UUID.fromString(playlist.get("id").asString());
        var originalCreate = new EngagementCommand(1, UUID.fromString(playlist.get("version").asString()), user,
                Action.PLAYLIST_CREATE, null, id, "Kafka playlist", null, null, Instant.now());
        var createPlan = operations.find(user, originalCreate.operationId()).orElseThrow().plan();
        complete("PUT", "/api/playlists/" + id + "/videos/" + video, null);
        assertThat(playlists.find(user, id).orElseThrow().items()).extracting(PlaylistStore.Item::videoId).containsExactly(video);
        complete("PATCH", "/api/playlists/" + id, "{\"title\":\"Renamed\"}");
        assertThat(playlists.find(user, id).orElseThrow().title()).isEqualTo("Renamed");
        complete("DELETE", "/api/playlists/" + id, null);
        assertThat(playlists.find(user, id)).isEmpty();
        assertThatThrownBy(() -> writer.apply(originalCreate, createPlan)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(playlists.find(user, id)).isEmpty();
        var automatic = complete("POST", "/api/playlists/automatic/refresh", null);
        assertThat(automatic.get("playlist").get("title").asString()).isEqualTo("For you");
    }

    @Test void restartAfterPartialRemovalRepairsAllIndexesUsingThePersistedPlan() throws Exception {
        complete("PUT", "/api/liked-videos/" + video, null);
        var command = new EngagementCommand(1, UUID.randomUUID(), user, Action.UNLIKE, video, null, null, null, null, Instant.now());
        operations.register(command);
        var plan = operations.prepare(command, planner.prepare(command, operations.nextWriteMicros(user, System.currentTimeMillis() * 1000)));
        // Simulate failure between the lookup delete and the denormalized index deletes.
        session.execute(SimpleStatement.newInstance("DELETE FROM " + KEYSPACE + ".like_lookup WHERE user_id = ? AND video_id = ?", user, video)
                .setQueryTimestamp(plan.writeMicros()));
        var reopened = new ScyllaOperationStore(session, KEYSPACE, json);
        assertThat(reopened.find(user, command.operationId()).orElseThrow().plan()).isNotNull();
        kafka.send(TOPIC, Long.toString(user), json.writeValueAsString(command)).get(10, TimeUnit.SECONDS);
        await(command.operationId());
        try (var rows = likes.newestFirst(user)) { assertThat(rows.toList()).isEmpty(); }
        assertThat(likes.isLiked(user, video)).isFalse();
    }

    @Test void malformedMessagesGoToDeadLetterWithoutBlockingTheNextValidAction() throws Exception {
        var config = new Properties();
        config.put("bootstrap.servers", "localhost:9092"); config.put("group.id", "test-dlt-" + UUID.randomUUID());
        config.put("auto.offset.reset", "earliest"); config.put("enable.auto.commit", "false");
        try (var consumer = new KafkaConsumer<String, String>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC + "-dlt"));
            kafka.send(TOPIC, Long.toString(user), "broken-envelope").get(10, TimeUnit.SECONDS);
            complete("PUT", "/api/saved-videos/" + video, null);
            boolean found = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!found && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) if (record.value().equals("broken-envelope")) found = true;
            }
            assertThat(found).isTrue();
            assertThat(saved.isSaved(user, video)).isTrue();
        }
    }

    @Test void staleProjectionRetryCannotOverwriteLaterClearOrUnlike() throws Exception {
        UUID watch = UUID.randomUUID();
        submit("POST", "/api/history", "{\"videoId\":" + video + "}", watch); await(watch);
        var oldWatch = operations.find(user, watch).orElseThrow();
        complete("DELETE", "/api/history", null);
        writer.apply(oldWatch.command(), oldWatch.plan()); // Simulates an old in-flight database retry.
        assertThat(history.page(user, 0, 20)).isEmpty();
        UUID like = UUID.randomUUID(); submit("PUT", "/api/liked-videos/" + video, null, like); await(like);
        var oldLike = operations.find(user, like).orElseThrow();
        complete("DELETE", "/api/liked-videos/" + video, null);
        writer.apply(oldLike.command(), oldLike.plan());
        assertThat(likes.isLiked(user, video)).isFalse();
        try (var rows = likes.newestFirst(user)) { assertThat(rows.toList()).isEmpty(); }
    }

    @Test void transientFailureDoesNotLetLaterUnlikeOvertakeTheLike() throws Exception {
        registry.stop();
        UUID like = UUID.randomUUID(), unlike = UUID.randomUUID();
        try {
            submit("PUT", "/api/liked-videos/" + video, null, like);
            catalog.setDown(true);
            submit("DELETE", "/api/liked-videos/" + video, null, unlike);
            registry.start();
            Thread.sleep(1500); // Give the listener time to encounter the simulated catalog outage.
            assertThat(operations.find(user, unlike).orElseThrow().state()).isEqualTo("PENDING");
        } finally { catalog.setDown(false); registry.start(); }
        await(like); await(unlike);
        assertThat(likes.isLiked(user, video)).isFalse();
    }

    @Test void operationReceiptsRequireAuthenticationAndNeverExposeAnotherUsersResult() throws Exception {
        UUID operation = UUID.randomUUID();
        submit("POST", "/api/history", "{\"videoId\":" + video + "}", operation); await(operation);
        mvc.perform(get("/api/engagement/operations/" + operation)).andExpect(status().isUnauthorized());
        String stranger = tokens.issue(new SignedInUser(user + 1, null, null));
        mvc.perform(get("/api/engagement/operations/" + operation).header("Authorization", "Bearer " + stranger))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.result").isEmpty());
    }

    @AfterAll void cleanupIsolatedTestData() throws Exception {
        registry.stop();
        session.execute(SimpleStatement.newInstance("DROP KEYSPACE IF EXISTS " + KEYSPACE).setTimeout(Duration.ofSeconds(60)));
        try (var admin = Admin.create(Map.of("bootstrap.servers", "localhost:9092"))) {
            Set<String> topics = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            var ours = topics.stream().filter(name -> name.startsWith(TOPIC)).toList();
            if (!ours.isEmpty()) admin.deleteTopics(ours).all().get(20, TimeUnit.SECONDS);
        }
    }
}
