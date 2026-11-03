package ITubeBackend.payments;

import ITubeBackend.dto.memberships.MembershipEvent;
import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.entities.ChannelMembershipTierEntity;
import ITubeBackend.entities.PaymentCustomerEntity;
import ITubeBackend.repository.ChannelMembershipRepository;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import ITubeBackend.repository.PaymentCustomerRepository;
import ITubeBackend.service.entitlement.MembershipAccessPolicy;
import ITubeBackend.service.payments.MembershipSyncService;
import ITubeBackend.service.payments.OutboxWriter;
import ITubeBackend.service.payments.StripeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static ITubeBackend.payments.StripeFixtures.CLOCK;
import static ITubeBackend.payments.StripeFixtures.inDays;
import static ITubeBackend.payments.StripeFixtures.paymentProperties;
import static ITubeBackend.payments.StripeFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MembershipSyncServiceTest {
    private static final String SUB = "sub_1";
    private static final String CUSTOMER = "cus_1";

    private final StripeApi stripe = mock(StripeApi.class);
    private final ChannelMembershipTierRepository tierRepository = mock(ChannelMembershipTierRepository.class);
    private final PaymentCustomerRepository customerRepository = mock(PaymentCustomerRepository.class);
    private final ChannelMembershipRepository membershipRepository = mock(ChannelMembershipRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final Map<String, ChannelMembershipEntity> stored = new HashMap<>();
    private MembershipSyncService service;

    @BeforeEach
    void setUp() {
        service = new MembershipSyncService(stripe, tierRepository, customerRepository, membershipRepository,
                new MembershipAccessPolicy(paymentProperties(true), CLOCK), outbox, CLOCK,
                "membership.activated", "membership.expired", "membership.tier-changed");
        when(tierRepository.findByStripeProductId("prod_supporter")).thenReturn(Optional.of(tier(10L, 1)));
        when(tierRepository.findByStripeProductId("prod_vip")).thenReturn(Optional.of(tier(11L, 3)));
        when(customerRepository.findByStripeCustomerId(CUSTOMER)).thenReturn(Optional.of(new PaymentCustomerEntity(7L, CUSTOMER)));

        // A small in-memory stand-in for the memberships table
        AtomicLong ids = new AtomicLong(100);
        when(membershipRepository.findForUpdateBySubscriptionId(anyString()))
                .thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));
        when(membershipRepository.saveAndFlush(any())).thenAnswer(call -> {
            ChannelMembershipEntity membership = call.getArgument(0);
            if (membership.getMembershipId() == null) ReflectionTestUtils.setField(membership, "membershipId", ids.incrementAndGet());
            stored.put(membership.getStripeSubscriptionId(), membership);
            return membership;
        });
        when(membershipRepository.findLive(any(), any())).thenAnswer(call -> stored.values().stream()
                .filter(m -> m.getUserId().equals(call.getArgument(0)) && m.getChannelId().equals(call.getArgument(1)))
                .filter(m -> ChannelMembershipRepository.LIVE_STATUSES.contains(m.getStatus()))
                .findFirst());
    }

    @Test
    void activeSubscriptionCreatesMembershipAndPublishesActivatedOnce() throws Exception {
        when(stripe.retrieveSubscription(SUB)).thenReturn(subscription(SUB, CUSTOMER, "prod_supporter", "active", inDays(30), false));

        ChannelMembershipEntity membership = service.sync(SUB).orElseThrow();

        assertThat(membership.getUserId()).isEqualTo(7L);
        assertThat(membership.getChannelId()).isEqualTo(5L);
        assertThat(membership.isEntitled()).isTrue();
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).write(eq("membership.activated"), eq("101"), any(), event.capture());
        assertThat(((MembershipEvent) event.getValue()).tierLevel()).isEqualTo(1);
    }

    @Test
    void duplicateWebhooksDoNotPublishASecondActivation() throws Exception {
        when(stripe.retrieveSubscription(SUB)).thenReturn(subscription(SUB, CUSTOMER, "prod_supporter", "active", inDays(30), false));

        // checkout.session.completed, customer.subscription.created and invoice.paid all sync the same subscription
        service.sync(SUB);
        service.sync(SUB);
        service.sync(SUB);

        verify(outbox, times(1)).write(eq("membership.activated"), any(), any(), any());
        assertThat(stored).hasSize(1);
    }

    @Test
    void outOfOrderEventsConvergeOnStripesCurrentState() throws Exception {
        // The subscription was created then immediately cancelled. Whichever event arrives first,
        // the sync reads Stripe's current state (canceled), so access is never granted.
        when(stripe.retrieveSubscription(SUB)).thenReturn(subscription(SUB, CUSTOMER, "prod_supporter", "canceled", inDays(30), false));

        service.sync(SUB); // customer.subscription.deleted processed first
        service.sync(SUB); // late customer.subscription.created

        assertThat(stored.get(SUB).isEntitled()).isFalse();
        verify(outbox, never()).write(any(), any(), any(), any());
    }

    @Test
    void cancellationAtPeriodEndKeepsAccessUntilStripeEndsTheSubscription() throws Exception {
        when(stripe.retrieveSubscription(SUB))
                .thenReturn(subscription(SUB, CUSTOMER, "prod_supporter", "active", inDays(30), false))
                .thenReturn(subscription(SUB, CUSTOMER, "prod_supporter", "active", inDays(30), true))
                .thenReturn(subscription(SUB, CUSTOMER, "prod_supporter", "canceled", inDays(30), true));

        service.sync(SUB);
        service.sync(SUB);
        assertThat(stored.get(SUB).isCancelAtPeriodEnd()).isTrue();
        assertThat(stored.get(SUB).isEntitled()).isTrue();

        service.sync(SUB); // customer.subscription.deleted at period end
        assertThat(stored.get(SUB).isEntitled()).isFalse();
        verify(outbox).write(eq("membership.expired"), any(), any(), any());
    }

    @Test
    void failedRenewalFollowsThePastDuePolicyAndUnpaidExpires() throws Exception {
        when(stripe.retrieveSubscription(SUB))
                .thenReturn(subscription(SUB, CUSTOMER, "prod_supporter", "active", inDays(30), false))
                .thenReturn(subscription(SUB, CUSTOMER, "prod_supporter", "past_due", inDays(30), false))
                .thenReturn(subscription(SUB, CUSTOMER, "prod_supporter", "unpaid", inDays(30), false));

        service.sync(SUB);
        service.sync(SUB); // invoice.payment_failed: Stripe is retrying, access kept
        assertThat(stored.get(SUB).isEntitled()).isTrue();

        service.sync(SUB); // retries exhausted
        assertThat(stored.get(SUB).isEntitled()).isFalse();
        verify(outbox).write(eq("membership.expired"), any(), any(), any());
    }

    @Test
    void upgradeKeepsOneMembershipAndPublishesTierChanged() throws Exception {
        when(stripe.retrieveSubscription(SUB))
                .thenReturn(subscription(SUB, CUSTOMER, "prod_supporter", "active", inDays(30), false))
                .thenReturn(subscription(SUB, CUSTOMER, "prod_vip", "active", inDays(30), false));

        service.sync(SUB);
        service.sync(SUB);

        assertThat(stored).hasSize(1);
        assertThat(stored.get(SUB).getTierId()).isEqualTo(11L);
        verify(outbox).write(eq("membership.tier-changed"), any(), any(), any());
        verify(outbox, times(1)).write(eq("membership.activated"), any(), any(), any());
    }

    @Test
    void secondLiveSubscriptionForTheSameChannelIsCancelledAndRefunded() throws Exception {
        when(stripe.retrieveSubscription(SUB)).thenReturn(subscription(SUB, CUSTOMER, "prod_supporter", "active", inDays(30), false));
        when(stripe.retrieveSubscription("sub_2")).thenReturn(subscription("sub_2", CUSTOMER, "prod_vip", "active", inDays(30), false));
        service.sync(SUB);

        service.sync("sub_2");

        verify(stripe).cancelAndRefundDuplicate("sub_2");
        assertThat(stored.get("sub_2").getStatus()).isEqualTo("canceled");
        assertThat(stored.get("sub_2").isEntitled()).isFalse();
        verify(outbox, times(1)).write(eq("membership.activated"), any(), any(), any());
    }

    @Test
    void subscriptionsForOtherProductsAreIgnored() throws Exception {
        when(stripe.retrieveSubscription("sub_premium")).thenReturn(subscription("sub_premium", CUSTOMER, "prod_premium", "active", inDays(30), false));
        assertThat(service.sync("sub_premium")).isEmpty();
        verify(membershipRepository, never()).saveAndFlush(any());
    }

    private static ChannelMembershipTierEntity tier(Long id, int level) {
        ChannelMembershipTierEntity tier = new ChannelMembershipTierEntity();
        ReflectionTestUtils.setField(tier, "tierId", id);
        tier.setChannelId(5L);
        tier.setLevel(level);
        tier.setName("Tier " + level);
        return tier;
    }
}
