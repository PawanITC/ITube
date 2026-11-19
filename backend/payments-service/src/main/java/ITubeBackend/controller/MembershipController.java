package ITubeBackend.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import ITubeBackend.dto.memberships.ChangeTierRequest;
import ITubeBackend.dto.memberships.ChannelTiersDto;
import ITubeBackend.dto.memberships.CreateTierRequest;
import ITubeBackend.dto.memberships.MembershipDto;
import ITubeBackend.dto.memberships.TierDto;
import ITubeBackend.dto.memberships.UpdateTierRequest;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.memberships.MembershipService;
import ITubeBackend.service.memberships.MembershipTierService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Paid channel memberships. Free Subscribe stays in ChannelController and never touches Stripe. */
@Tag(name = "Memberships", description = "Paid channel membership tiers and viewer memberships")
@RestController
@RequestMapping("/api/memberships")
public class MembershipController {
    private final MembershipTierService tierService;
    private final MembershipService membershipService;

    public MembershipController(MembershipTierService tierService, MembershipService membershipService) {
        this.tierService = tierService;
        this.membershipService = membershipService;
    }

    @GetMapping("/channels/{channelId}/tiers")
    public ChannelTiersDto getChannelTiers(@PathVariable Long channelId) {
        return tierService.getChannelTiers(channelId, CurrentUser.find().orElse(null));
    }

    @PostMapping("/channels/{channelId}/tiers")
    @ResponseStatus(HttpStatus.CREATED)
    public TierDto createTier(@PathVariable Long channelId, @Valid @RequestBody CreateTierRequest request) {
        return tierService.createTier(CurrentUser.require(), channelId, request);
    }

    @PatchMapping("/tiers/{tierId}")
    public TierDto updateTier(@PathVariable Long tierId, @Valid @RequestBody UpdateTierRequest request) {
        return tierService.updateTier(CurrentUser.require(), tierId, request);
    }

    @DeleteMapping("/tiers/{tierId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveTier(@PathVariable Long tierId) {
        tierService.archiveTier(CurrentUser.require(), tierId);
    }

    @GetMapping("/me")
    public List<MembershipDto> getMyMemberships() {
        return membershipService.getMyMemberships(CurrentUser.require());
    }

    @PostMapping("/{membershipId}/tier")
    public MembershipDto changeTier(@PathVariable Long membershipId, @Valid @RequestBody ChangeTierRequest request) {
        return membershipService.changeTier(CurrentUser.require(), membershipId, request.tierId());
    }

    @PostMapping("/{membershipId}/cancel")
    public MembershipDto cancel(@PathVariable Long membershipId) {
        return membershipService.cancel(CurrentUser.require(), membershipId);
    }

    @PostMapping("/{membershipId}/resume")
    public MembershipDto resume(@PathVariable Long membershipId) {
        return membershipService.resume(CurrentUser.require(), membershipId);
    }
}
