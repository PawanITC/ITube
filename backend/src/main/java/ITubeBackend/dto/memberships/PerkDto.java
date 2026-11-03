package ITubeBackend.dto.memberships;

import ITubeBackend.entities.PerkType;

/** A perk shown on a tier. fromLevel is the tier level that introduced it, so higher tiers show inherited perks. */
public record PerkDto(PerkType type, String description, int fromLevel) {
}
