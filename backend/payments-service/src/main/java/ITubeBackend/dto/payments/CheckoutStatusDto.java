package ITubeBackend.dto.payments;

import ITubeBackend.dto.memberships.MembershipDto;

/**
 * Shown on the Checkout return page. status is the local Checkout state (OPEN, COMPLETE, EXPIRED);
 * access is only granted once the webhook has created the membership.
 */
public record CheckoutStatusDto(String status, String purpose, Long channelId, MembershipDto membership) {
}
