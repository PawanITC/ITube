package ITubeBackend.payments;

import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.entities.ChannelMembershipTierEntity;
import ITubeBackend.repository.ChannelMembershipRepository;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import ITubeBackend.repository.PaymentCustomerRepository;
import ITubeBackend.service.memberships.MembershipService;
import ITubeBackend.service.memberships.MembershipViews;
import ITubeBackend.service.payments.MembershipSyncService;
import ITubeBackend.service.payments.PaymentRateLimiter;
import ITubeBackend.service.payments.StripeApi;
import com.stripe.model.Subscription;
import com.stripe.param.SubscriptionUpdateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static ITubeBackend.payments.StripeFixtures.inDays;
import static ITubeBackend.payments.StripeFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MembershipServiceTest {
    private final ChannelMembershipRepository membershipRepository = mock(ChannelMembershipRepository.class);
    private final ChannelMembershipTierRepository tierRepository = mock(ChannelMembershipTierRepository.class);
    private final MembershipSyncService membershipSync = mock(MembershipSyncService.class);
    private final StripeApi stripe = mock(StripeApi.class);
    private MembershipService service;
    private ChannelMembershipEntity membership;

    @BeforeEach
    void setUp() throws Exception {
        service = new MembershipService(membershipRepository, tierRepository, mock(PaymentCustomerRepository.class),
                membershipSync, mock(MembershipViews.class), mock(PaymentRateLimiter.class), stripe);
        membership = new ChannelMembershipEntity(7L, 5L, "sub_1", "cus_1");
        membership.setTierId(11L);
        membership.setStatus("active");
        when(membershipRepository.findById(1L)).thenReturn(Optional.of(membership));
        when(tierRepository.findById(10L)).thenReturn(Optional.of(tier(10L, 1, "price_supporter")));
        when(tierRepository.findById(11L)).thenReturn(Optional.of(tier(11L, 2, "price_member")));
        when(tierRepository.findById(12L)).thenReturn(Optional.of(tier(12L, 3, "price_vip")));
        when(stripe.retrieveSubscription("sub_1")).thenReturn(subscription("sub_1", "cus_1", "prod_member", "active", inDays(20), false));
        when(stripe.updateSubscription(eq("sub_1"), any(), anyString())).thenReturn(new Subscription());
        when(membershipSync.apply(any())).thenReturn(Optional.of(membership));
    }

    @Test
    void upgradeChargesTheDifferenceNowAndOnlyAppliesIfPaid() throws Exception {
        service.changeTier(7L, 1L, 12L);

        Map<String, Object> sent = capturedUpdate().toMap();
        assertThat(sent).containsEntry("proration_behavior", "always_invoice")
                .containsEntry("payment_behavior", "pending_if_incomplete");
        assertThat(sent.get("items").toString()).contains("price_vip").contains("si_sub_1");
    }

    @Test
    void downgradeCreditsTheUnusedTimeToTheNextInvoice() throws Exception {
        service.changeTier(7L, 1L, 10L);

        assertThat(capturedUpdate().toMap()).containsEntry("proration_behavior", "create_prorations")
                .doesNotContainKey("payment_behavior");
    }

    @Test
    void failedUpgradePaymentLeavesTheTierUnchanged() throws Exception {
        Subscription pending = new Subscription();
        pending.setPendingUpdate(new Subscription.PendingUpdate());
        when(stripe.updateSubscription(eq("sub_1"), any(), anyString())).thenReturn(pending);

        assertThatThrownBy(() -> service.changeTier(7L, 1L, 12L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));
    }

    @Test
    void cancelSetsCancelAtPeriodEnd() throws Exception {
        service.cancel(7L, 1L);
        assertThat(capturedUpdate().toMap()).containsEntry("cancel_at_period_end", true);
    }

    @Test
    void usersCannotManageSomeoneElsesMembership() {
        assertThatThrownBy(() -> service.cancel(8L, 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(stripe);
    }

    private SubscriptionUpdateParams capturedUpdate() throws Exception {
        ArgumentCaptor<SubscriptionUpdateParams> params = ArgumentCaptor.forClass(SubscriptionUpdateParams.class);
        verify(stripe).updateSubscription(eq("sub_1"), params.capture(), anyString());
        return params.getValue();
    }

    private static ChannelMembershipTierEntity tier(Long id, int level, String priceId) {
        ChannelMembershipTierEntity tier = new ChannelMembershipTierEntity();
        ReflectionTestUtils.setField(tier, "tierId", id);
        tier.setChannelId(5L);
        tier.setLevel(level);
        tier.setStripePriceId(priceId);
        return tier;
    }
}
