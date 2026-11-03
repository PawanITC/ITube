package ITubeBackend.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import ITubeBackend.service.payments.StripeWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe calls this endpoint. It has no user session; the Stripe signature is its authentication,
 * and it is always checked against the raw request bytes.
 */
@Tag(name = "Stripe webhooks", description = "Called by Stripe; requires a valid Stripe-Signature")
@RestController
public class StripeWebhookController {
    private final StripeWebhookService webhookService;

    public StripeWebhookController(StripeWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/api/webhooks/stripe")
    public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
                                        @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        webhookService.receive(rawBody, signature);
        return ResponseEntity.ok().build();
    }
}
