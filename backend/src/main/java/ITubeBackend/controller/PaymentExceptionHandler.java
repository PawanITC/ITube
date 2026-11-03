package ITubeBackend.controller;

import ITubeBackend.service.payments.StripeCallException;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns Stripe failures into a generic problem response. Logs only Stripe's error code and
 * request id, never request bodies, customer data or keys.
 */
@RestControllerAdvice
public class PaymentExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentExceptionHandler.class);

    @ExceptionHandler({StripeException.class, StripeCallException.class})
    public ProblemDetail handleStripe(Exception exception) {
        StripeException stripe = exception instanceof StripeCallException wrapped
                ? wrapped.stripeException() : (StripeException) exception;
        log.error("Stripe call failed: type={} code={} status={} requestId={}",
                stripe.getClass().getSimpleName(), stripe.getCode(), stripe.getStatusCode(), stripe.getRequestId());
        if (stripe instanceof CardException) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, "Your payment method was declined");
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The payment provider could not complete the request. Please try again.");
    }
}
