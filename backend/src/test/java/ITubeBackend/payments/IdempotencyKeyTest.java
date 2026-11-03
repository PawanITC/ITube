package ITubeBackend.payments;

import ITubeBackend.dto.accounts.AccountDto;
import ITubeBackend.dto.memberships.CreateTierRequest;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.entities.ChannelMembershipTierEntity;
import ITubeBackend.repository.AccountRepository;
import ITubeBackend.repository.ChannelMembershipRepository;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.MembershipPerkRepository;
import ITubeBackend.repository.PaymentCustomerRepository;
import ITubeBackend.service.memberships.MembershipTierService;
import ITubeBackend.service.memberships.MembershipViews;
import ITubeBackend.service.payments.StripeApi;
import ITubeBackend.service.payments.StripeCustomerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static ITubeBackend.payments.StripeFixtures.paymentProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Local ids repeat across databases (a reset dev DB, or teammates sharing one Stripe sandbox), and
 * Stripe rejects a reused idempotency key sent with different parameters. Keys must never be built
 * from local ids alone.
 */
class IdempotencyKeyTest {
    @Test
    void sameLocalTierIdInTwoDatabasesGetsDifferentProductKeys() throws Exception {
        StripeApi stripe = mock(StripeApi.class);
        when(stripe.createProduct(anyString(), any(), any(), anyMap(), anyString())).thenReturn("prod_1");
        when(stripe.createMonthlyPrice(anyString(), anyLong(), anyString(), anyString())).thenReturn("price_1");

        // Two fresh databases both hand out tier id 1
        newTierService(stripe).createTier(99L, 5L, new CreateTierRequest("Supporter", null, 1, 299L, List.of()));
        newTierService(stripe).createTier(99L, 5L, new CreateTierRequest("Different tier", null, 1, 499L, List.of()));

        ArgumentCaptor<String> productKeys = ArgumentCaptor.forClass(String.class);
        verify(stripe, times(2)).createProduct(anyString(), any(), any(), anyMap(), productKeys.capture());
        assertThat(productKeys.getAllValues()).doesNotHaveDuplicates().allMatch(key -> key.startsWith("tier-product-1-"));
        ArgumentCaptor<String> priceKeys = ArgumentCaptor.forClass(String.class);
        verify(stripe, times(2)).createMonthlyPrice(anyString(), anyLong(), anyString(), priceKeys.capture());
        assertThat(priceKeys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void sameLocalUserIdInTwoDatabasesGetsDifferentCustomerKeys() throws Exception {
        StripeApi stripe = mock(StripeApi.class);
        when(stripe.createCustomer(anyLong(), anyString(), anyString())).thenReturn("cus_1");
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findDtoById(2L)).thenReturn(new AccountDto(2L, "Dev", "One", "one@itube.local"));
        PaymentCustomerRepository customers = mock(PaymentCustomerRepository.class);
        when(customers.findByUserId(2L)).thenReturn(Optional.empty());

        new StripeCustomerService(customers, accounts, stripe).getOrCreateCustomerId(2L);
        new StripeCustomerService(customers, accounts, stripe).getOrCreateCustomerId(2L);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(stripe, times(2)).createCustomer(eq(2L), anyString(), keys.capture());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
    }

    private static MembershipTierService newTierService(StripeApi stripe) {
        ChannelRepository channels = mock(ChannelRepository.class);
        ChannelEntity channel = new ChannelEntity();
        channel.setChannelName("Demo");
        when(channels.findById(5L)).thenReturn(Optional.of(channel));
        when(channels.findOwnerUserIdByChannelId(5L)).thenReturn(Optional.of(99L));
        ChannelMembershipTierRepository tiers = mock(ChannelMembershipTierRepository.class);
        when(tiers.saveAndFlush(any())).thenAnswer(call -> {
            ChannelMembershipTierEntity tier = call.getArgument(0);
            ReflectionTestUtils.setField(tier, "tierId", 1L);
            return tier;
        });
        when(tiers.findByChannelIdAndActiveTrueOrderByLevelAsc(5L)).thenAnswer(call -> {
            ChannelMembershipTierEntity tier = new ChannelMembershipTierEntity();
            ReflectionTestUtils.setField(tier, "tierId", 1L);
            tier.setChannelId(5L);
            tier.setLevel(1);
            tier.setPriceMinor(299L);
            tier.setCurrency("gbp");
            return List.of(tier);
        });
        return new MembershipTierService(channels, tiers, mock(MembershipPerkRepository.class),
                mock(ChannelMembershipRepository.class), mock(MembershipViews.class), stripe, paymentProperties(true));
    }
}
