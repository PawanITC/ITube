package ITubeBackend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * The signed-in user of the current request. Endpoints must use this instead of trusting a user id sent by the
 * client.
 * <p>
 * The user comes from the ITube access token (a signed JWT, see {@link AccessTokens}), verified by
 * {@link AuthenticationConfig.AuthenticationFilter} in every service. Nothing is looked up: no Redis, no call to
 * identity-service. The display name is in the token too, so e.g. posting a comment needs no call to identity.
 */
public final class CurrentUser {
    /** Request attribute holding the {@link SignedInUser}. */
    public static final String ATTRIBUTE = CurrentUser.class.getName();

    private CurrentUser() {
    }

    public static Optional<SignedInUser> user() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes != null
                && attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof SignedInUser user
                ? Optional.of(user) : Optional.empty();
    }

    public static Optional<SignedInUser> user(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof SignedInUser user ? Optional.of(user) : Optional.empty();
    }

    public static Optional<Long> find() {
        return user().map(SignedInUser::userId);
    }

    public static Long require() {
        return require("Sign in first");
    }

    /** The user id, or 401 with this message. */
    public static Long require(String message) {
        return find().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, message));
    }

    /** The user's primary channel when the token was issued (it can be newer: see identity's refresh). */
    public static Optional<Long> channelId() {
        return user().map(SignedInUser::channelId);
    }

    public static Optional<String> displayName() {
        return user().map(SignedInUser::displayName).filter(name -> !name.isBlank());
    }
}
