package ITubeBackend.common.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Load shedding: each pod works on at most {@code itube.load-shedding.max-concurrent-requests} requests at once.
 * Beyond that it answers 503 + Retry-After straight away instead of accepting more work.
 * <p>
 * Why: with virtual threads a pod accepts any number of requests, so under overload every request slows down
 * together until all of them time out (the load test showed 20-40 s responses). Refusing the excess in microseconds
 * keeps the accepted requests fast, lets the gateway retry another pod, and gives the autoscaler time to add pods.
 * A short wait ({@code queue-timeout}) absorbs tiny bursts without refusing anyone.
 * <p>
 * Metrics: itube.load_shedding.rejected (count), itube.load_shedding.in_flight (gauge).
 */
@Configuration
public class LoadShedding {

    @Bean
    FilterRegistrationBean<Filter> loadSheddingFilter(
            @Value("${itube.load-shedding.max-concurrent-requests:400}") int maxConcurrent,
            @Value("${itube.load-shedding.queue-timeout-ms:50}") long queueTimeoutMs,
            ObjectProvider<MeterRegistry> meters) {
        Filter filter = new Filter(maxConcurrent, queueTimeoutMs);
        meters.ifAvailable(filter::bindTo);
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        // Before anything else does work (sessions, security, controllers)
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    public static class Filter extends OncePerRequestFilter {
        private final Semaphore permits;
        private final int maxConcurrent;
        private final long queueTimeoutMs;
        private Counter rejected;

        Filter(int maxConcurrent, long queueTimeoutMs) {
            this.maxConcurrent = maxConcurrent;
            this.permits = new Semaphore(maxConcurrent);
            this.queueTimeoutMs = queueTimeoutMs;
        }

        void bindTo(MeterRegistry registry) {
            rejected = Counter.builder("itube.load_shedding.rejected")
                    .description("Requests refused with 503 because the pod was at its concurrency limit").register(registry);
            Gauge.builder("itube.load_shedding.in_flight", permits, p -> maxConcurrent - p.availablePermits())
                    .description("Requests being processed right now").register(registry);
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            // Kubernetes probes must always get through, or an overloaded pod would be restarted
            return request.getRequestURI().startsWith("/actuator/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            boolean acquired;
            try {
                acquired = permits.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                acquired = false;
            }
            if (!acquired) {
                if (rejected != null) rejected.increment();
                response.setHeader("Retry-After", "1");
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Busy, try again shortly");
                return;
            }
            try {
                chain.doFilter(request, response);
            } finally {
                permits.release();
            }
        }
    }
}
