package ITubeBackend.dto.video;

import java.time.OffsetDateTime;

/**
 * Result of GET /api/videos/{id}/playback-authorisation.
 * When allowed, append {@code ?token=} to the file and rendition URLs; the token expires at expiresAt.
 * When refused, reason is SIGN_IN_REQUIRED, MEMBERSHIP_REQUIRED, HIGHER_TIER_REQUIRED or PRIVATE.
 */
public record PlaybackAuthorisationDto(
        Long videoId,
        Long channelId,
        String visibility,
        Integer requiredTierLevel,
        boolean allowed,
        String reason,
        String sourceUrl,
        String token,
        OffsetDateTime expiresAt) {
}
