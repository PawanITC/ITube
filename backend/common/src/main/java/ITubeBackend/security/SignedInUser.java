package ITubeBackend.security;

/**
 * Who is making the request, as stated by a verified ITube access token (JWT). Everything here comes from the
 * token's claims, so reading it needs no database or Redis lookup.
 *
 * @param userId      the account id (claim {@code sub})
 * @param channelId   the user's primary channel when the token was issued, or null (claim {@code cid})
 * @param displayName "Firstname Lastname" when the token was issued, or null (claim {@code name})
 */
public record SignedInUser(Long userId, Long channelId, String displayName) {
}
