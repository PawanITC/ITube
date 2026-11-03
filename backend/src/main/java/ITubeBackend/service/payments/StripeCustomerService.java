package ITubeBackend.service.payments;

import ITubeBackend.dto.accounts.AccountDto;
import ITubeBackend.entities.PaymentCustomerEntity;
import ITubeBackend.repository.AccountRepository;
import ITubeBackend.repository.PaymentCustomerRepository;
import com.stripe.exception.StripeException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StripeCustomerService {
    private final PaymentCustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final StripeApi stripe;

    public StripeCustomerService(PaymentCustomerRepository customerRepository, AccountRepository accountRepository,
                                 StripeApi stripe) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.stripe = stripe;
    }

    /**
     * Returns the user's Stripe Customer id, creating the customer on first use.
     * Callers must hold the per-user lock ({@link PaymentCustomerRepository#lockUser}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String getOrCreateCustomerId(long userId) {
        return customerRepository.findByUserId(userId)
                .map(PaymentCustomerEntity::getStripeCustomerId)
                .orElseGet(() -> create(userId));
    }

    private String create(long userId) {
        AccountDto account = accountRepository.findDtoById(userId);
        if (account == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account not found");
        try {
            // Same key on retry returns the same customer instead of creating a second one
            String customerId = stripe.createCustomer(userId, account.email(), "itube-customer-" + userId);
            customerRepository.save(new PaymentCustomerEntity(userId, customerId));
            return customerId;
        } catch (StripeException exception) {
            throw new StripeCallException(exception);
        }
    }

    @Transactional(readOnly = true)
    public String requireCustomerId(long userId) {
        return customerRepository.findByUserId(userId)
                .map(PaymentCustomerEntity::getStripeCustomerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You have no billing account yet"));
    }
}
