package ITubeBackend.payments;

import ITubeBackend.dto.subscribtions.SubscriptionStatusDto;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.SubscriptionRepository;
import ITubeBackend.service.channel.SubscriptionService;
import ITubeBackend.common.client.PaymentsClient;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Free Subscribe and paid Join are separate features: subscribing must never involve billing. */
class FreeSubscribeTest {
    @Test
    void subscribingNeverInvolvesStripe() {
        ChannelRepository channels = mock(ChannelRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        when(channels.findOwnerUserIdByChannelId(5L)).thenReturn(Optional.of(99L));
        when(subscriptions.countByChannelId(5L)).thenReturn(1L);

        SubscriptionStatusDto status = new SubscriptionService(channels, subscriptions, ITubeBackend.support.CatalogTestSupport.singleShard(),
                new org.springframework.transaction.support.TransactionTemplate(
                        mock(org.springframework.transaction.PlatformTransactionManager.class))).subscribe(7L, 5L);

        assertThat(status.subscribed()).isTrue();
        verify(subscriptions).addSubscription(7L, 5L);
        // Billing lives in payments-service, and subscribing has no client for it, so it can't trigger billing
        assertThat(Arrays.stream(SubscriptionService.class.getDeclaredFields()).map(f -> f.getType()))
                .doesNotContain((Class) PaymentsClient.class);
    }
}
