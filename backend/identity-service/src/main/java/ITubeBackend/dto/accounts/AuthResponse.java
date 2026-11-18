package ITubeBackend.dto.accounts;

/** The signed-in user, returned by register, login and /api/auth/me. Never includes the password hash. */
public record AuthResponse(Long userId, String firstname, String lastname, String email,
                           Long channelId, String channelName) {
}
