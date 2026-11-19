package ITubeBackend.entities;

/** Benefits a creator can attach to a membership tier. Only MEMBERS_ONLY_VIDEOS is enforced so far. */
public enum PerkType {
    MEMBERS_ONLY_VIDEOS,
    MEMBERS_ONLY_SHORTS,
    MEMBERS_ONLY_LIVESTREAMS,
    EARLY_ACCESS,
    COMMUNITY_POSTS,
    LOYALTY_BADGE,
    CUSTOM_EMOJI,
    MEMBERS_ONLY_CHAT,
    CUSTOM
}
