package ITubeBackend.controller;

import ITubeBackend.dto.accounts.AccountDto;
import ITubeBackend.repository.AccountRepository;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API (never routed by the gateway; guarded by the internal token).
 * payments-service needs the email for Stripe customers; engagement-service the name for old sessions.
 */
@Hidden
@RestController
@RequestMapping("/internal/accounts")
public class InternalAccountController {
    private final AccountRepository accountRepository;

    public InternalAccountController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<AccountDto> account(@PathVariable Long userId) {
        AccountDto account = accountRepository.findDtoById(userId);
        return account == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(account);
    }
}
