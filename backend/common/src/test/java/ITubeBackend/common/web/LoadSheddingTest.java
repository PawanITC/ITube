package ITubeBackend.common.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LoadSheddingTest {

    @Test
    void refusesWorkBeyondTheLimitAtOnceAndRecoversWhenRequestsFinish() throws Exception {
        LoadShedding.Filter filter = new LoadShedding.Filter(1, 10);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FilterChain slow = (request, response) -> {
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                filter.doFilter(new MockHttpServletRequest("GET", "/api/videos"), new MockHttpServletResponse(), slow);
                return null;
            });
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            // The only permit is taken: the next request is refused straight away
            MockHttpServletResponse refused = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/videos"), refused, (req, res) -> {
            });
            assertThat(refused.getStatus()).isEqualTo(503);
            assertThat(refused.getHeader("Retry-After")).isEqualTo("1");

            // Health probes are never refused
            MockHttpServletResponse probe = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health/liveness"), probe, (req, res) -> {
            });
            assertThat(probe.getStatus()).isEqualTo(200);

            release.countDown();
        }
        MockHttpServletResponse later = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/videos"), later, (req, res) -> {
        });
        assertThat(later.getStatus()).isEqualTo(200);
    }
}
