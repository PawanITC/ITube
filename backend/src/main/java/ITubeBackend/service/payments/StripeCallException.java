package ITubeBackend.service.payments;

import com.stripe.exception.StripeException;

/** Unchecked wrapper so Stripe failures roll back the surrounding transaction. */
public class StripeCallException extends RuntimeException {
    public StripeCallException(StripeException cause) {
        super(cause);
    }

    public StripeException stripeException() {
        return (StripeException) getCause();
    }
}
