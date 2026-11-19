package ITubeBackend.common.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Another ITube service could not answer: timed out, returned 5xx, its circuit breaker is open, or too many
 * calls to it are already in flight. Callers either fall back (e.g. History without video details) or let this
 * propagate, which the client sees as 503 Service Unavailable with Retry-After.
 */
public class DownstreamUnavailableException extends ResponseStatusException {
    private final String downstream;

    public DownstreamUnavailableException(String downstream, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "The " + downstream + " service is temporarily unavailable, try again shortly",
                cause);
        this.downstream = downstream;
    }

    public String downstream() {
        return downstream;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");
        return headers;
    }
}
