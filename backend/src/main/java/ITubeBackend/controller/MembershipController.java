package ITubeBackend.controller;

import ITubeBackend.dto.memberships.ChangeTierRequest;
import ITubeBackend.dto.memberships.ChannelTiersDto;
import ITubeBackend.dto.memberships.CreateTierRequest;
import ITubeBackend.dto.memberships.MembershipDto;
import ITubeBackend.dto.memberships.TierDto;
import ITubeBackend.dto.memberships.UpdateTierRequest;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.memberships.MembershipService;
import ITubeBackend.service.memberships.MembershipTierService;
import jakarta.servlet.http.HttpSession;
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
    public ChannelTiersDto getChannelTiers(@PathVariable Long channelId, HttpSession session) {
        return tierService.getChannelTiers(channelId, CurrentUser.find(session).orElse(null));
    }

    @PostMapping("/channels/{channelId}/tiers")
    @ResponseStatus(HttpStatus.CREATED)
    public TierDto createTier(@PathVariable Long channelId, @Valid @RequestBody CreateTierRequest request,
                              HttpSession session) {
        return tierService.createTier(CurrentUser.require(session), channelId, request);
    }

    @PatchMapping("/tiers/{tierId}")
    public TierDto updateTier(@PathVariable Long tierId, @Valid @RequestBody UpdateTierRequest request,
                              HttpSession session) {
        return tierService.updateTier(CurrentUser.require(session), tierId, request);
    }

    @DeleteMapping("/tiers/{tierId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveTier(@PathVariable Long tierId, HttpSession session) {
        tierService.archiveTier(CurrentUser.require(session), tierId);
    }

    @GetMapping("/me")
    public List<MembershipDto> getMyMemberships(HttpSession session) {
        return membershipService.getMyMemberships(CurrentUser.require(session));
    }

    @PostMapping("/{membershipId}/tier")
    public MembershipDto changeTier(@PathVariable Long membershipId, @Valid @RequestBody ChangeTierRequest request,
                                    HttpSession session) {
        return membershipService.changeTier(CurrentUser.require(session), membershipId, request.tierId());
    }

    @PostMapping("/{membershipId}/cancel")
    public MembershipDto cancel(@PathVariable Long membershipId, HttpSession session) {
        return membershipService.cancel(CurrentUser.require(session), membershipId);
    }

    @PostMapping("/{membershipId}/resume")
    public MembershipDto resume(@PathVariable Long membershipId, HttpSession session) {
        return membershipService.resume(CurrentUser.require(session), membershipId);
    }
}
