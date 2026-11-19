package ITubeBackend.controller;

import ITubeBackend.config.StripeProperties;
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
 * Turns Stripe failures into a problem response. Stripe's error messages never contain card data or
 * keys, so they're logged; request bodies and customer data are not. Callers get a generic message
 * unless detailed errors are switched on for development (never with a live key).
 */
@RestControllerAdvice
public class PaymentExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentExceptionHandler.class);

    private final StripeProperties stripeProperties;

    public PaymentExceptionHandler(StripeProperties stripeProperties) {
        this.stripeProperties = stripeProperties;
    }

    @ExceptionHandler({StripeException.class, StripeCallException.class})
    public ProblemDetail handleStripe(Exception exception) {
        StripeException stripe = exception instanceof StripeCallException wrapped
                ? wrapped.stripeException() : (StripeException) exception;
        String reason = reason(stripe);
        log.error("Stripe call failed: type={} code={} status={} requestId={} message={}",
                stripe.getClass().getSimpleName(), stripe.getCode(), stripe.getStatusCode(), stripe.getRequestId(), reason);

        ProblemDetail problem = stripe instanceof CardException
                ? ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, "Your payment method was declined")
                : ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                        "The payment provider could not complete the request. Please try again.");
        if (stripeProperties.showDetailedErrors()) {
            problem.setDetail("Stripe: " + reason);
            problem.setProperty("stripeErrorType", stripe.getClass().getSimpleName());
            problem.setProperty("stripeCode", stripe.getCode());
            problem.setProperty("stripeRequestId", stripe.getRequestId());
        }
        return problem;
    }

    /** Stripe's own explanation, without the "; code: ...; request-id: ..." suffix getMessage() adds. */
    private static String reason(StripeException stripe) {
        if (stripe.getStripeError() != null && stripe.getStripeError().getMessage() != null) {
            return stripe.getStripeError().getMessage();
        }
        String message = stripe.getMessage();
        if (message == null) return stripe.getClass().getSimpleName();
        int cut = message.indexOf("; code:");
        if (cut < 0) cut = message.indexOf("; request-id:");
        return cut < 0 ? message : message.substring(0, cut);
    }
}
