package ITubeBackend.service.payments;

import ITubeBackend.repository.WebhookInboxRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

/**
 * Verifies a Stripe webhook and stores it durably. Processing happens later in
 * {@link WebhookProcessor}, so Stripe gets a fast 200 and a failure in our code can be retried.
 */
@Service
public class StripeWebhookService {
    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final StripeApi stripe;
    private final WebhookInboxRepository inboxRepository;
    private final MeterRegistry meterRegistry;

    public StripeWebhookService(StripeApi stripe, WebhookInboxRepository inboxRepository, MeterRegistry meterRegistry) {
        this.stripe = stripe;
        this.inboxRepository = inboxRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void receive(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Stripe-Signature header");
        }
        // The signature covers the exact bytes Stripe sent, so verify before any parsing
        String payload = new String(rawBody, StandardCharsets.UTF_8);
        Event event;
        try {
            event = stripe.constructEvent(payload, signatureHeader);
        } catch (SignatureVerificationException exception) {
            meterRegistry.counter("itube.stripe.webhook.invalid_signature").increment();
            log.warn("Rejected Stripe webhook with an invalid signature");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid signature");
        }
        boolean stored = inboxRepository.insertIfAbsent(event.getId(), event.getType(),
                Boolean.TRUE.equals(event.getLivemode()), payload) == 1;
        meterRegistry.counter("itube.stripe.webhook.received", "type", event.getType()).increment();
        log.info("Stripe event {} {} {}", event.getId(), event.getType(), stored ? "stored" : "already received");
    }
}
