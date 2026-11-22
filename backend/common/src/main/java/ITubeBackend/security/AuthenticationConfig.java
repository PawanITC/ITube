package ITubeBackend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

/**
 * Reads the access token on every request and, when it's valid, makes the user available through
 * {@link CurrentUser}. A missing, expired or forged token just means "not signed in": public endpoints still work,
 * and endpoints that need a user answer 401 (the frontend then refreshes the token and retries).
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthenticationConfig {

    @Bean
    AccessTokens accessTokens(AuthProperties properties, Clock clock) {
        return new AccessTokens(properties, clock);
    }

    @Bean
    FilterRegistrationBean<AuthenticationFilter> authenticationFilter(AccessTokens tokens) {
        FilterRegistrationBean<AuthenticationFilter> registration = new FilterRegistrationBean<>(new AuthenticationFilter(tokens));
        // Right after load shedding, before any controller
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    public static class AuthenticationFilter extends OncePerRequestFilter {
        private static final String BEARER = "Bearer ";
        private final AccessTokens tokens;

        public AuthenticationFilter(AccessTokens tokens) {
            this.tokens = tokens;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getRequestURI();
            return path.startsWith("/actuator/") || path.startsWith("/internal/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String token = token(request);
            if (token != null) {
                tokens.verify(token).ifPresent(user -> request.setAttribute(CurrentUser.ATTRIBUTE, user));
            }
            chain.doFilter(request, response);
        }

        /** {@code Authorization: Bearer} (API clients, load tests) wins over the cookie (the browser). */
        private static String token(HttpServletRequest request) {
            String header = request.getHeader("Authorization");
            if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
                return header.substring(BEARER.length()).trim();
            }
            Cookie[] cookies = request.getCookies();
            if (cookies == null) return null;
            for (Cookie cookie : cookies) {
                if (AccessTokens.COOKIE.equals(cookie.getName())) return cookie.getValue();
            }
            return null;
        }
    }
}
