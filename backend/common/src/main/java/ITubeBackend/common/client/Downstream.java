package ITubeBackend.common.client;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

/**
 * One downstream ITube service (e.g. "catalog") as seen by a caller: a RestClient with connect/read timeouts,
 * wrapped in Resilience4j patterns. Calls go through, outermost first:
 * <ol>
 *   <li><b>Retry</b> - transient failures (I/O errors, 5xx) are retried with exponential backoff and jitter,
 *       so a single dropped connection or a pod being replaced is invisible to the user.</li>
 *   <li><b>Circuit breaker</b> - when most recent calls fail or are slow, stop calling for a while. A struggling
 *       service gets room to recover instead of being hammered, and callers fail in microseconds, not seconds.</li>
 *   <li><b>Bulkhead</b> - caps concurrent calls to this service, so one slow dependency can't tie up every
 *       request thread of the caller (the failure stays in its own compartment, like a ship's bulkhead).</li>
 * </ol>
 * 4xx answers (e.g. 404) are normal results, never retried and never counted as failures.
 */
public final class Downstream {
    private final String name;
    private final RestClient rest;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public Downstream(String name, RestClient rest, Retry retry, CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
        this.name = name;
        this.rest = rest;
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
    }

    public String name() {
        return name;
    }

    public RestClient rest() {
        return rest;
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    /** Runs {@code call} with retry, circuit breaker and bulkhead; outages become {@link DownstreamUnavailableException}. */
    public <T> T call(Supplier<T> call) {
        Supplier<T> guarded = Bulkhead.decorateSupplier(bulkhead, call);
        guarded = CircuitBreaker.decorateSupplier(circuitBreaker, guarded);
        guarded = Retry.decorateSupplier(retry, guarded);
        try {
            return guarded.get();
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException
                 | HttpServerErrorException exception) {
            throw new DownstreamUnavailableException(name, exception);
        }
    }

    static boolean isTransient(Throwable throwable) {
        return throwable instanceof ResourceAccessException || throwable instanceof HttpServerErrorException;
    }
}
