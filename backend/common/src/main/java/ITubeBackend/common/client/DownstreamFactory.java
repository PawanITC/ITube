package ITubeBackend.common.client;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds one {@link Downstream} per service name, sharing Resilience4j registries so circuit breaker state,
 * retries and bulkhead usage show up in /actuator/metrics (resilience4j.circuitbreaker.state etc.).
 */
public class DownstreamFactory {
    private final RestClient.Builder restClientBuilder;
    private final ResilienceProperties properties;
    private final InternalApi internalApi;
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final BulkheadRegistry bulkheads;
    private final Map<String, Downstream> created = new ConcurrentHashMap<>();

    public DownstreamFactory(RestClient.Builder restClientBuilder, ResilienceProperties properties,
                             InternalApi internalApi, ObjectProvider<MeterRegistry> meterRegistry) {
        this.restClientBuilder = restClientBuilder;
        this.properties = properties;
        this.internalApi = internalApi;

        var cb = properties.circuitBreaker();
        this.circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(cb.failureRateThreshold())
                .slowCallRateThreshold(cb.failureRateThreshold())
                .slowCallDurationThreshold(cb.slowCallDurationThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.slidingWindowSize())
                .minimumNumberOfCalls(cb.minimumNumberOfCalls())
                .waitDurationInOpenState(cb.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(Downstream::isTransient)
                .build());

        var retry = properties.retry();
        this.retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(Math.max(1, retry.maxAttempts()))
                // 100ms, ~200ms, ~400ms ... with +-50% jitter so retrying clients don't stampede in sync
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(retry.initialInterval(), 2.0, 0.5))
                .retryOnException(Downstream::isTransient)
                // An open breaker means "don't call"; retrying would just wait for nothing
                .ignoreExceptions(CallNotPermittedException.class)
                .build());

        this.bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(properties.bulkhead().maxConcurrentCalls())
                .maxWaitDuration(Duration.ZERO)
                .build());

        meterRegistry.ifAvailable(registry -> {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers).bindTo(registry);
            TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(registry);
            TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(registry);
        });
    }

    public Downstream create(String name, String baseUrl) {
        return created.computeIfAbsent(name, key -> build(key, baseUrl));
    }

    private Downstream build(String name, String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient rest = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> internalApi.applyTo(headers))
                .build();
        CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(name);
        Retry retry = retries.retry(name);
        Bulkhead bulkhead = bulkheads.bulkhead(name);
        return new Downstream(name, rest, retry, circuitBreaker, bulkhead);
    }
}
