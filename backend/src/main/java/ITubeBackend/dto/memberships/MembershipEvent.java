package ITubeBackend.dto.memberships;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Kafka payload for membership.activated / membership.expired / membership.tier-changed.
 * Contains internal ids and state only, no names or emails. Consumers should dedupe on eventId.
 */
public record MembershipEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        OffsetDateTime occurredAt,
        Long membershipId,
        Long userId,
        Long channelId,
        Long tierId,
        int tierLevel,
        String status,
        OffsetDateTime accessUntil) {
}
