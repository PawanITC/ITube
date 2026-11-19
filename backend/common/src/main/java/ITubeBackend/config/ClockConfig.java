package ITubeBackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {
    /** Injected wherever time matters so tests can control it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
