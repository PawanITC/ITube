package ITubeBackend.e2e;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** One user's "browser": an HTTP client with its own cookie jar (so its own session), talking to the gateway. */
public class Browser {
    static final String BASE_URL = System.getProperty("e2e.baseUrl", "http://localhost:8080").replaceAll("/+$", "");
    static final ObjectMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public record Response(int status, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    public Response delete(String path) {
        return send(HttpRequest.newBuilder(uri(path)).DELETE());
    }

    public Response put(String path) {
        return send(HttpRequest.newBuilder(uri(path)).PUT(HttpRequest.BodyPublishers.noBody()));
    }

    public Response postJson(String path, String json) {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    public Response patchJson(String path, String json) {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)));
    }

    /** PUT raw bytes to any URL (an upload part goes straight to object storage, like in the browser). */
    public Response putBytes(String absoluteOrRelativeUrl, byte[] bytes) {
        URI target = absoluteOrRelativeUrl.startsWith("http") ? URI.create(absoluteOrRelativeUrl) : uri(absoluteOrRelativeUrl);
        return send(HttpRequest.newBuilder(target).header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)));
    }

    private static URI uri(String path) {
        return URI.create(BASE_URL + path);
    }

    private Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not reach " + BASE_URL + " - is the stack running? " + exception, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
