package ITubeBackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import History.HistoryRepository;
import History.WatchHistory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class HistoryApiTests {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Autowired
    private HistoryRepository historyRepository;

    @Test
    void historyIsPaginatedNewestFirstWithIdAsTieBreaker() throws Exception {
        Instant older = Instant.parse("2026-01-01T10:00:00Z");
        Instant newer = Instant.parse("2026-01-02T10:00:00Z");
        // Insert out of watch-time order to prove we sort by time, not just ID.
        var entries = historyRepository.saveAll(List.of(
                new WatchHistory("page-test-user", 11L, newer),
                new WatchHistory("page-test-user", 12L, older),
                new WatchHistory("page-test-user", 13L, newer)
        ));
        ObjectMapper mapper = new ObjectMapper();
        var first = mapper.readTree(getUri(URI.create(historyUri("page-test-user") + "&page=0&size=2")).body());
        assertEquals(2, first.size());
        assertEquals(entries.get(2).getId().longValue(), first.get(0).get("id").asLong());
        assertEquals(entries.get(0).getId().longValue(), first.get(1).get("id").asLong());
        var second = mapper.readTree(getUri(URI.create(historyUri("page-test-user") + "&page=1&size=2")).body());
        assertEquals(1, second.size());
        assertEquals(entries.get(1).getId().longValue(), second.get(0).get("id").asLong());
        assertEquals("[]", getUri(URI.create(historyUri("page-test-user") + "&page=2&size=2")).body());
    }

    @Test
    void repeatWatchesCreateSeparateHistoryEntries() throws Exception {
        var first = post("repeat-test-user", "{\"videoId\":25}");
        var second = post("repeat-test-user", "{\"videoId\":25}");
        assertEquals(201, first.statusCode());
        assertEquals(201, second.statusCode());
        ObjectMapper mapper = new ObjectMapper();
        assertTrue(!mapper.readTree(first.body()).get("id").equals(mapper.readTree(second.body()).get("id")));
        assertEquals(2, mapper.readTree(get("repeat-test-user").body()).size());
    }

    @Test
    void invalidParametersAndMalformedBodiesReturnBadRequest() throws Exception {
        assertEquals(400, getUri(URI.create("http://localhost:" + port + "/api/history")).statusCode());
        assertEquals(400, get("").statusCode());
        assertEquals(400, get("x".repeat(256)).statusCode());
        for (String query : List.of("&page=-1", "&size=0", "&size=101", "&page=abc")) {
            assertEquals(400, getUri(URI.create(historyUri("validation-test-user") + query)).statusCode());
        }
        assertEquals(400, post("validation-test-user", "{broken").statusCode());
        assertEquals(400, post("validation-test-user", "{\"videoId\":null}").statusCode());
        assertEquals(400, delete(0, "validation-test-user").statusCode());
        assertEquals(400, delete(-1, "validation-test-user").statusCode());
        assertEquals(404, delete(Long.MAX_VALUE, "validation-test-user").statusCode());
        assertEquals("[]", get("validation-test-user").body());
    }

    @Test
    void clearingHistoryRemovesOnlyTheSelectedUsersEntries() throws Exception {
        assertEquals(201, post("clear-test-user", "{\"videoId\":25}").statusCode());
        assertEquals(201, post("clear-test-user", "{\"videoId\":26}").statusCode());
        assertEquals(201, post("clear-other-user", "{\"videoId\":27}").statusCode());
        String otherHistory = get("clear-other-user").body();

        assertEquals(400, clear("").statusCode());
        assertEquals(2, new ObjectMapper().readTree(get("clear-test-user").body()).size());

        HttpResponse<String> cleared = clear("clear-test-user");
        assertEquals(204, cleared.statusCode());
        assertEquals("", cleared.body());
        assertEquals("[]", get("clear-test-user").body());
        assertEquals(otherHistory, get("clear-other-user").body());
        assertEquals(204, clear("clear-test-user").statusCode());
    }

    private HttpResponse<String> clear(String userId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(historyUri(userId)).DELETE().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void deletionChecksOwnerAndRemovesOnlyTheSelectedEntry() throws Exception {
        HttpResponse<String> first = post("delete-test-user", "{\"videoId\":25}");
        HttpResponse<String> second = post("delete-test-user", "{\"videoId\":26}");
        assertEquals(201, first.statusCode());
        assertEquals(201, second.statusCode());
        ObjectMapper mapper = new ObjectMapper();
        long id = mapper.readTree(first.body()).get("id").asLong();

        assertEquals(404, delete(id, "different-user").statusCode());
        assertEquals(2, mapper.readTree(get("delete-test-user").body()).size());

        HttpResponse<String> deleted = delete(id, "delete-test-user");
        assertEquals(204, deleted.statusCode());
        assertEquals("", deleted.body());
        var remaining = mapper.readTree(get("delete-test-user").body());
        assertEquals(1, remaining.size());
        assertEquals(mapper.readTree(second.body()).get("id"), remaining.get(0).get("id"));
        assertEquals(404, delete(id, "delete-test-user").statusCode());
    }

    private HttpResponse<String> delete(long id, String userId) throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/api/history/" + id + "?userId=" + userId);
        HttpRequest request = HttpRequest.newBuilder(uri).DELETE().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void savedHistoryIsReturnedOnlyForItsUser() throws Exception {
        HttpResponse<String> saved = post("history-test-user", "{\"videoId\":25}");

        assertEquals(201, saved.statusCode());
        assertTrue(saved.body().contains("\"videoId\":25"));
        assertTrue(saved.body().contains("\"id\":"));
        assertTrue(saved.body().contains("\"watchedAt\":"));

        HttpResponse<String> history = get("history-test-user");
        assertEquals(200, history.statusCode());
        ObjectMapper mapper = new ObjectMapper();
        var savedJson = mapper.readTree(saved.body());
        var historyJson = mapper.readTree(history.body());
        assertEquals(1, historyJson.size());
        assertEquals(savedJson.get("id"), historyJson.get(0).get("id"));
        assertEquals(savedJson.get("videoId"), historyJson.get(0).get("videoId"));
        Instant savedTime = Instant.parse(savedJson.get("watchedAt").asString());
        Instant retrievedTime = Instant.parse(historyJson.get(0).get("watchedAt").asString());
        // The database stores microseconds, while Java Instant supports nanoseconds.
        assertTrue(Math.abs(java.time.Duration.between(savedTime, retrievedTime).toNanos()) < 1_000);

        HttpResponse<String> otherUserHistory = get("another-test-user");
        assertEquals(200, otherUserHistory.statusCode());
        assertEquals("[]", otherUserHistory.body());
    }

    @Test
    void invalidRequestsDoNotCreateHistory() throws Exception {
        assertEquals(400, post("invalid-test-user", "{}").statusCode());
        assertEquals(400, post("invalid-test-user", "{\"videoId\":0}").statusCode());
        assertEquals(400, post("invalid-test-user", "{\"videoId\":-1}").statusCode());
        assertEquals(400, post("", "{\"videoId\":25}").statusCode());
        assertEquals("[]", get("invalid-test-user").body());
    }

    private HttpResponse<String> post(String userId, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(historyUri(userId))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String userId) throws Exception {
        return getUri(historyUri(userId));
    }

    private HttpResponse<String> getUri(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI historyUri(String userId) {
        return URI.create("http://localhost:" + port + "/api/history?userId=" + userId);
    }
}
