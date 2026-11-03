package ITubeBackend.controller;

import ITubeBackend.dto.payments.CheckoutResponse;
import ITubeBackend.dto.payments.CheckoutStatusDto;
import ITubeBackend.dto.payments.MembershipCheckoutRequest;
import ITubeBackend.dto.payments.PortalResponse;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.payments.CheckoutService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final CheckoutService checkoutService;

    public PaymentController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping("/checkout/channel-membership")
    public CheckoutResponse startMembershipCheckout(@Valid @RequestBody MembershipCheckoutRequest request,
                                                    HttpSession session) {
        return checkoutService.startMembershipCheckout(CurrentUser.require(session), request.tierId());
    }

    @GetMapping("/checkout/sessions/{checkoutSessionId}")
    public CheckoutStatusDto getCheckoutStatus(@PathVariable String checkoutSessionId, HttpSession session) {
        return checkoutService.getStatus(CurrentUser.require(session), checkoutSessionId);
    }

    @PostMapping("/billing-portal")
    public PortalResponse openBillingPortal(HttpSession session) {
        return new PortalResponse(checkoutService.createBillingPortalUrl(CurrentUser.require(session)));
    }
}
