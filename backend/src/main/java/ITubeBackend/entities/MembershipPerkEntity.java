package ITubeBackend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "membership_perks")
public class MembershipPerkEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "perk_id")
    private Long perkId;

    @Column(name = "tier_id", nullable = false)
    private Long tierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "perk_type", nullable = false, length = 40)
    private PerkType perkType;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    public MembershipPerkEntity() {
    }

    public MembershipPerkEntity(Long tierId, PerkType perkType, String description) {
        this.tierId = tierId;
        this.perkType = perkType;
        this.description = description;
    }

    public Long getTierId() { return tierId; }
    public PerkType getPerkType() { return perkType; }
    public String getDescription() { return description; }
}
