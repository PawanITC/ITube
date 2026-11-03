package ITubeBackend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Header text for the generated API docs. The endpoints themselves are read from the controllers
 * by springdoc, so the docs only ever show APIs that actually exist.
 */
@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI itubeOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ITube API")
                .version("0.2.0")
                .description("""
                        REST API for the ITube video platform, generated from the Spring controllers.

                        **Signing in (temporary, until login is finished):** call `POST /api/session/dev` \
                        or `POST /api/session/dev/random` with **Try it out**. The browser keeps the session \
                        cookie, so the other endpoints then run as that user. The user is always taken from \
                        the session, never from the request.

                        **Money** is in minor units (pence) with a lowercase currency, and prices always come \
                        from the server.

                        `POST /api/webhooks/stripe` is called by Stripe, not by clients; it rejects any \
                        request without a valid Stripe signature."""));
    }
}
