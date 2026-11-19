package ITubeBackend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Header text for the generated API docs. The endpoints themselves are read from the controllers
 * by springdoc, so the docs only ever show APIs that actually exist. Each service publishes its own
 * spec at /v3/api-docs; through the gateway they are at /api-docs/{service}.
 */
@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI itubeOpenApi(@Value("${spring.application.name}") String service) {
        return new OpenAPI().info(new Info()
                .title("ITube API - " + service)
                .version("0.3.0")
                .description("""
                        Part of the ITube REST API, generated from this service's Spring controllers. All services \
                        are reached through the API gateway at the same origin, so paths are the same as before \
                        the split into microservices.

                        **Signing in:** call `POST /api/auth/register` or `POST /api/auth/login` (identity-service) \
                        with **Try it out**. The browser keeps the access token cookie (a signed JWT), which every \
                        service verifies, so the other endpoints then run as that user. API clients can send it as \
                        `Authorization: Bearer <token>` instead. The user is always taken from the token, never \
                        from the request.

                        **Money** is in minor units (pence) with a lowercase currency, and prices always come \
                        from the server."""));
    }
}
