package ITubeBackend.common.client;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Service-to-service endpoints live under {@code /internal/**}. Two layers keep them private:
 * <ol>
 *   <li>the API gateway never routes {@code /internal/} (browsers can't reach it at all), and</li>
 *   <li>when {@code itube.internal.token} is set, every service rejects internal calls without that shared
 *       secret in {@value #HEADER} (defence in depth if something inside the cluster is compromised).</li>
 * </ol>
 */
public class InternalApi {
    public static final String HEADER = "X-Internal-Token";
    public static final String PATH_PREFIX = "/internal/";

    private final byte[] token;

    public InternalApi(String token) {
        this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    public void applyTo(HttpHeaders headers) {
        if (token != null) headers.set(HEADER, new String(token, StandardCharsets.UTF_8));
    }

    boolean accepts(String presented) {
        if (token == null) return true;
        if (presented == null) return false;
        // Constant-time comparison, so the token can't be guessed byte by byte from response times
        return MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Servlet filter that guards /internal/** with the shared token. */
    public static class Guard extends OncePerRequestFilter {
        private final InternalApi internalApi;

        public Guard(InternalApi internalApi) {
            this.internalApi = internalApi;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith(PATH_PREFIX);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!internalApi.accepts(request.getHeader(HEADER))) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Internal API");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
