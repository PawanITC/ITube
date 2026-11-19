package ITubeBackend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Reads the signed-in user from the server-side session. Endpoints must use this instead of trusting a user id
 * sent by the client.
 * <p>
 * The session lives in Redis and is shared by every service (same SESSION cookie), so identity-service signs the
 * user in and catalog/engagement/payments read the same attributes without calling identity. The display name is
 * stored too, so e.g. posting a comment needs no call to identity-service at all.
 */
public final class CurrentUser {
    public static final String USER_ID = "userId";
    public static final String CHANNEL_ID = "channelId";
    public static final String DISPLAY_NAME = "displayName";

    private CurrentUser() {
    }

    public static Optional<Long> find(HttpSession session) {
        return session != null && session.getAttribute(USER_ID) instanceof Long id ? Optional.of(id) : Optional.empty();
    }

    public static Long require(HttpSession session) {
        return find(session).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Start a session first"));
    }

    public static Optional<String> displayName(HttpSession session) {
        return session != null && session.getAttribute(DISPLAY_NAME) instanceof String name && !name.isBlank()
                ? Optional.of(name) : Optional.empty();
    }

    /** Starts a fresh session for the user (new id on sign-in, so a planted session id can't be reused). */
    public static void signIn(HttpServletRequest request, Long userId, Long channelId, String displayName) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(USER_ID, userId);
        if (channelId != null) session.setAttribute(CHANNEL_ID, channelId);
        else session.removeAttribute(CHANNEL_ID);
        if (displayName != null) session.setAttribute(DISPLAY_NAME, displayName);
        else session.removeAttribute(DISPLAY_NAME);
    }
}
