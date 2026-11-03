package ITubeBackend.payments;

import ITubeBackend.dto.payments.CheckoutResponse;
import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.entities.ChannelMembershipTierEntity;
import ITubeBackend.entities.CheckoutSessionEntity;
import ITubeBackend.repository.ChannelMembershipRepository;
import ITubeBackend.repository.ChannelMembershipTierRepository;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.CheckoutSessionRepository;
import ITubeBackend.repository.PaymentCustomerRepository;
import ITubeBackend.service.memberships.MembershipViews;
import ITubeBackend.service.payments.CheckoutService;
import ITubeBackend.service.payments.PaymentRateLimiter;
import ITubeBackend.service.payments.StripeApi;
import ITubeBackend.service.payments.StripeCustomerService;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ITubeBackend.payments.StripeFixtures.CLOCK;
import static ITubeBackend.payments.StripeFixtures.inDays;
import static ITubeBackend.payments.StripeFixtures.paymentProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CheckoutServiceTest {
    private static final long USER = 7L;
    private static final long CHANNEL = 5L;
    private static final long CREATOR = 99L;

    private final ChannelMembershipTierRepository tierRepository = mock(ChannelMembershipTierRepository.class);
    private final ChannelRepository channelRepository = mock(ChannelRepository.class);
    private final ChannelMembershipRepository membershipRepository = mock(ChannelMembershipRepository.class);
    private final CheckoutSessionRepository checkoutRepository = mock(CheckoutSessionRepository.class);
    private final PaymentCustomerRepository customerRepository = mock(PaymentCustomerRepository.class);
    private final StripeCustomerService customerService = mock(StripeCustomerService.class);
    private final PaymentRateLimiter rateLimiter = mock(PaymentRateLimiter.class);
    private final StripeApi stripe = mock(StripeApi.class);
    private CheckoutService service;

    @BeforeEach
    void setUp() {
        service = new CheckoutService(tierRepository, channelRepository, membershipRepository, checkoutRepository,
                customerRepository, customerService, mock(MembershipViews.class), rateLimiter, stripe,
                paymentProperties(true), CLOCK);
        when(tierRepository.findById(10L)).thenReturn(Optional.of(tier(10L, "price_supporter")));
        when(tierRepository.findById(11L)).thenReturn(Optional.of(tier(11L, "price_vip")));
        when(channelRepository.findOwnerUserIdByChannelId(CHANNEL)).thenReturn(Optional.of(CREATOR));
        when(membershipRepository.findLive(USER, CHANNEL)).thenReturn(Optional.empty());
        when(customerService.getOrCreateCustomerId(USER)).thenReturn("cus_1");
    }

    @Test
    void createsSubscriptionCheckoutWithTheServerSidePrice() throws Exception {
        Session session = new Session();
        session.setId("cs_new");
        session.setUrl("https://checkout.stripe.com/c/pay/cs_new");
        session.setExpiresAt(inDays(1).toEpochSecond());
        when(stripe.createCheckoutSession(any(), anyString())).thenReturn(session);

        CheckoutResponse response = service.startMembershipCheckout(USER, 10L);

        assertThat(response.checkoutUrl()).isEqualTo(session.getUrl());
        ArgumentCaptor<SessionCreateParams> params = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(stripe).createCheckoutSession(params.capture(), anyString());
        Map<String, Object> sent = params.getValue().toMap();
        assertThat(sent).containsEntry("mode", "subscription").containsEntry("customer", "cus_1")
                .doesNotContainKey("payment_method_types");
        assertThat(sent.get("line_items").toString()).contains("price_supporter");
        assertThat(sent.get("success_url").toString()).contains("{CHECKOUT_SESSION_ID}");
        assertThat(sent.get("managed_payments").toString()).contains("enabled=false");
        assertThat(sent.get("subscription_data").toString()).contains("type=flexible");
        verify(customerRepository).lockUser(USER);
        verify(checkoutRepository).save(any());
    }

    @Test
    void existingMemberCannotStartASecondCheckout() {
        ChannelMembershipEntity existing = new ChannelMembershipEntity(USER, CHANNEL, "sub_1", "cus_1");
        existing.setTierId(10L);
        when(membershipRepository.findLive(USER, CHANNEL)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.startMembershipCheckout(USER, 11L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(stripe);
    }

    @Test
    void repeatedClickForTheSameTierReusesTheOpenSession() {
        CheckoutSessionEntity open = new CheckoutSessionEntity("cs_open", USER, "CHANNEL_MEMBERSHIP", CHANNEL, 10L,
                "https://checkout.stripe.com/c/pay/cs_open", inDays(1));
        when(checkoutRepository.findByUserIdAndPurposeAndChannelIdAndStatus(USER, "CHANNEL_MEMBERSHIP", CHANNEL, "OPEN"))
                .thenReturn(List.of(open));

        assertThat(service.startMembershipCheckout(USER, 10L).checkoutSessionId()).isEqualTo("cs_open");
        verifyNoInteractions(stripe);
    }

    @Test
    void choosingAnotherTierExpiresTheOldSessionSoBothCantBePaid() throws Exception {
        CheckoutSessionEntity open = new CheckoutSessionEntity("cs_open", USER, "CHANNEL_MEMBERSHIP", CHANNEL, 10L,
                "https://checkout.stripe.com/c/pay/cs_open", inDays(1));
        when(checkoutRepository.findByUserIdAndPurposeAndChannelIdAndStatus(USER, "CHANNEL_MEMBERSHIP", CHANNEL, "OPEN"))
                .thenReturn(List.of(open));
        Session session = new Session();
        session.setId("cs_vip");
        session.setExpiresAt(inDays(1).toEpochSecond());
        when(stripe.createCheckoutSession(any(), anyString())).thenReturn(session);

        service.startMembershipCheckout(USER, 11L);

        verify(stripe).expireCheckoutSession("cs_open");
        assertThat(open.getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void creatorsCannotJoinTheirOwnChannel() {
        assertThatThrownBy(() -> service.startMembershipCheckout(CREATOR, 10L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(stripe);
    }

    @Test
    void rateLimitIsCheckedBeforeAnythingElse() {
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)).when(rateLimiter).check("checkout", USER);
        assertThatThrownBy(() -> service.startMembershipCheckout(USER, 10L)).isInstanceOf(ResponseStatusException.class);
        verify(customerRepository, never()).lockUser(USER);
        verifyNoInteractions(stripe);
    }

    private static ChannelMembershipTierEntity tier(Long id, String priceId) {
        ChannelMembershipTierEntity tier = new ChannelMembershipTierEntity();
        ReflectionTestUtils.setField(tier, "tierId", id);
        tier.setChannelId(CHANNEL);
        tier.setStripePriceId(priceId);
        tier.setLevel(id.intValue() - 9);
        return tier;
    }
}
