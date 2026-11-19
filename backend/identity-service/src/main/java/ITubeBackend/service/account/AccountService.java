package ITubeBackend.service.account;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.dto.accounts.DevSessionDto;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.entities.AccountEntity;
import ITubeBackend.repository.AccountRepository;
import ITubeBackend.security.PasswordHasher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;


@Service
public class AccountService {

    private static final String DEV_EMAIL = "demo@itube.local";
    private static final String DEV_CHANNEL = "Demo Channel";
    private static final String DEV_VIEWER_EMAIL = "viewer@itube.local";
    private static final String DEV_VIEWER_CHANNEL = "Demo Viewer";

    private final AccountRepository accountRepository;
    private final CatalogClient catalog;
    private final PasswordHasher passwordHasher;

    public AccountService(AccountRepository accountRepository, CatalogClient catalog, PasswordHasher passwordHasher) {
        this.accountRepository = accountRepository;
        this.catalog = catalog;
        this.passwordHasher = passwordHasher;
    }

    // DELETE LATER - UNTIL LOGIN IS COMPLETE
    public DevSessionDto createDevSession() {
        return getOrCreateDevAccount(DEV_EMAIL, "Demo", "User", DEV_CHANNEL);
    }

    // DELETE LATER - UNTIL LOGIN IS COMPLETE. A fixed second user, so demos can switch between a
    // creator (createDevSession) and the same viewer without a private browser window.
    public DevSessionDto createDevViewerSession() {
        return getOrCreateDevAccount(DEV_VIEWER_EMAIL, "Demo", "Viewer", DEV_VIEWER_CHANNEL);
    }

    public AccountEntity requireAccount(Long userId) {
        return accountRepository.findById(userId).orElseThrow();
    }

    private DevSessionDto getOrCreateDevAccount(String email, String firstname, String lastname, String channelName) {
        AccountEntity account = accountRepository.findEntityByEmail(email).orElseGet(() -> {
            AccountEntity newAccount = new AccountEntity();
            newAccount.setFirstname(firstname);
            newAccount.setLastname(lastname);
            newAccount.setEmail(email);
            newAccount.setPasswordHash(passwordHasher.hash("itube-dev-only-password"));
            try {
                return accountRepository.saveAndFlush(newAccount);
            } catch (DataIntegrityViolationException raced) {
                return accountRepository.findEntityByEmail(email).orElseThrow();
            }
        });
        // Idempotent on the catalog side, so calling it on every dev sign-in is fine
        ChannelDto channel = catalog.ensureChannel(account.getUserId(), channelName);
        return new DevSessionDto(account.getUserId(), account.getEmail(), channel.channelId(), channel.channelName());
    }
}
