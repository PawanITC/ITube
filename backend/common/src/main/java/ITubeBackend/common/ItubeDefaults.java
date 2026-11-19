package ITubeBackend.common;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Adds {@code itube-defaults.properties} (settings every service shares: Redis, Kafka, OpenTelemetry, actuator,
 * resilience and cache defaults) with the <em>lowest</em> precedence. A service's own application.properties,
 * environment variables and command-line arguments all override it.
 * Registered in META-INF/spring.factories.
 */
public class ItubeDefaults implements EnvironmentPostProcessor {
    static final String NAME = "itubeDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(NAME)) return;
        try {
            environment.getPropertySources().addLast(
                    new ResourcePropertySource(NAME, new ClassPathResource("itube-defaults.properties")));
        } catch (IOException exception) {
            throw new UncheckedIOException("itube-defaults.properties is missing from itube-common", exception);
        }
    }
}
