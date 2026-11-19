package ITubeBackend.common.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** itube.resilience.* - timeouts, retry, circuit breaker and bulkhead for every downstream service. */
@ConfigurationProperties(prefix = "itube.resilience")
public record ResilienceProperties(Duration connectTimeout, Duration readTimeout, RetrySettings retry,
                                   CircuitBreakerSettings circuitBreaker, BulkheadSettings bulkhead) {

    public ResilienceProperties {
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(1);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(2);
        if (retry == null) retry = new RetrySettings(3, Duration.ofMillis(100));
        if (circuitBreaker == null) {
            circuitBreaker = new CircuitBreakerSettings(50, Duration.ofSeconds(2), 20, 10, Duration.ofSeconds(10));
        }
        if (bulkhead == null) bulkhead = new BulkheadSettings(100);
    }

    public record RetrySettings(int maxAttempts, Duration initialInterval) {
    }

    public record CircuitBreakerSettings(float failureRateThreshold, Duration slowCallDurationThreshold,
                                         int slidingWindowSize, int minimumNumberOfCalls,
                                         Duration waitDurationInOpenState) {
    }

    public record BulkheadSettings(int maxConcurrentCalls) {
    }
}
