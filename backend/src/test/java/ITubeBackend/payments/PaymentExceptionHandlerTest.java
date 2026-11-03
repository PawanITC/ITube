package ITubeBackend.payments;

import ITubeBackend.config.StripeProperties;
import ITubeBackend.controller.PaymentExceptionHandler;
import ITubeBackend.service.payments.StripeCallException;
import com.stripe.exception.IdempotencyException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentExceptionHandlerTest {
    private static final String REASON = "Keys for idempotent requests can only be used with the same parameters";

    private static ProblemDetail handle(String key, boolean detailedErrors) {
        StripeProperties properties = new StripeProperties(key, "whsec_dummy", 8, Duration.ofDays(30), detailedErrors);
        IdempotencyException stripe = new IdempotencyException(REASON, "req_123", "idempotency_error", 400);
        return new PaymentExceptionHandler(properties).handleStripe(new StripeCallException(stripe));
    }

    @Test
    void showsStripesReasonInDevelopmentWithATestKey() {
        ProblemDetail problem = handle("sk_test_dummy", true);
        assertThat(problem.getStatus()).isEqualTo(502);
        assertThat(problem.getDetail()).isEqualTo("Stripe: " + REASON);
        assertThat(problem.getProperties()).containsEntry("stripeRequestId", "req_123");
    }

    @Test
    void hidesStripesReasonByDefault() {
        ProblemDetail problem = handle("sk_test_dummy", false);
        assertThat(problem.getDetail()).doesNotContain("Stripe:").contains("payment provider");
        assertThat(problem.getProperties()).isNull();
    }

    @Test
    void neverShowsStripesReasonWithALiveKeyEvenIfSwitchedOn() {
        ProblemDetail problem = handle("sk_live_dummy", true);
        assertThat(problem.getDetail()).doesNotContain("Stripe:");
    }
}
