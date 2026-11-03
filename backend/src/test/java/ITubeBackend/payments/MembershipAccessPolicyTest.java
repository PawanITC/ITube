package ITubeBackend.payments;

import ITubeBackend.service.entitlement.MembershipAccessPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static ITubeBackend.payments.StripeFixtures.CLOCK;
import static ITubeBackend.payments.StripeFixtures.inDays;
import static ITubeBackend.payments.StripeFixtures.paymentProperties;
import static org.assertj.core.api.Assertions.assertThat;

class MembershipAccessPolicyTest {
    private final MembershipAccessPolicy policy = new MembershipAccessPolicy(paymentProperties(true), CLOCK);

    @ParameterizedTest
    @ValueSource(strings = {"active", "trialing"})
    void paidStatusesGrantAccessDuringThePeriod(String status) {
        assertThat(policy.hasAccess(status, inDays(10))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"canceled", "unpaid", "incomplete", "incomplete_expired", "paused"})
    void endedOrUnpaidStatusesNeverGrantAccess(String status) {
        assertThat(policy.hasAccess(status, inDays(10))).isFalse();
    }

    @Test
    void failedRenewalKeepsAccessWhileStripeRetriesWhenPolicyAllows() {
        assertThat(policy.hasAccess("past_due", inDays(10))).isTrue();
        MembershipAccessPolicy strict = new MembershipAccessPolicy(paymentProperties(false), CLOCK);
        assertThat(strict.hasAccess("past_due", inDays(10))).isFalse();
    }

    @Test
    void accessEndsAfterThePaidPeriodPlusGraceEvenIfStillActive() {
        // Safety net for a missed webhook: active in our DB but the period ended 2 days ago
        assertThat(policy.hasAccess("active", inDays(-2))).isFalse();
        // Within the 24h grace window
        assertThat(policy.hasAccess("active", inDays(0).minusHours(1))).isTrue();
    }
}
