package ITubeBackend.security;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Reads the signed-in user from the server-side session. Endpoints must use this instead of
 * trusting a user id sent by the client. When JWT login replaces dev sessions, only this class changes.
 */
public final class CurrentUser {
    private CurrentUser() {
    }

    public static Optional<Long> find(HttpSession session) {
        return session.getAttribute("userId") instanceof Long id ? Optional.of(id) : Optional.empty();
    }

    public static Long require(HttpSession session) {
        return find(session).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Start a session first"));
    }
}
