package ITubeBackend.dto.payments;

import jakarta.validation.constraints.NotNull;

/** Only the tier is sent; the price always comes from the database, never from the client. */
public record MembershipCheckoutRequest(@NotNull Long tierId) {
}
