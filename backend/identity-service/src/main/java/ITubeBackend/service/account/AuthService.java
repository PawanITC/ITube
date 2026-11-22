package ITubeBackend.service.account;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.client.DownstreamUnavailableException;
import ITubeBackend.dto.accounts.AuthResponse;
import ITubeBackend.dto.accounts.LoginRequest;
import ITubeBackend.dto.accounts.RegisterRequest;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.entities.AccountEntity;
import ITubeBackend.entities.ExternalIdentityEntity;
import ITubeBackend.repository.AccountRepository;
import ITubeBackend.repository.ExternalIdentityRepository;
import ITubeBackend.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Register and sign in. Accounts are ours (identity schema); channels belong to catalog-service.
 * <p>
 * Registration used to create the account and its channel in one database transaction. Now they are in different
 * services, so: (1) the account is committed here, then (2) the catalog is asked to create the channel (with retries
 * and a circuit breaker). If the catalog is down, registration still succeeds (channelId is null for now) and
 * account.created is published to Kafka; the catalog creates the channel when it reads the event. The catalog's
 * "ensure channel" is idempotent, so a retried call never creates a second channel.
 * No database transaction is held open while calling another service.
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String INVALID_LOGIN = "Incorrect email or password";

    private final AccountRepository accountRepository;
    private final CatalogClient catalog;
    private final AccountEvents events;
    private final PasswordHasher passwordHasher;
    private final TransactionTemplate transactions;
    private final ExternalIdentityRepository externalIdentities;
    private final Clock clock;
    // Compared against when the email is unknown, so a wrong email takes as long as a wrong password.
    private final String dummyHash;

    public AuthService(AccountRepository accountRepository, CatalogClient catalog, AccountEvents events,
                       PasswordHasher passwordHasher, TransactionTemplate transactions,
                       ExternalIdentityRepository externalIdentities, Clock clock) {
        this.accountRepository = accountRepository;
        this.catalog = catalog;
        this.events = events;
        this.passwordHasher = passwordHasher;
        this.transactions = transactions;
        this.externalIdentities = externalIdentities;
        this.clock = clock;
        this.dummyHash = passwordHasher.hash("itube-timing-equaliser");
    }

    public static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public static String displayName(AccountEntity account) {
        return (account.getFirstname() + " " + account.getLastname()).trim();
    }

    public AuthResponse register(RegisterRequest request) {
        String email = normaliseEmail(request.email());
        // Hash before the transaction: BCrypt is deliberately slow and must not hold a connection
        String passwordHash = passwordHasher.hash(request.password());
        AccountEntity account = transactions.execute(status -> {
            if (accountRepository.findEntityByEmail(email).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
            }
            AccountEntity created = new AccountEntity();
            created.setFirstname(request.firstname().trim());
            created.setLastname(request.lastname().trim());
            created.setEmail(email);
            created.setPasswordHash(passwordHash);
            try {
                return accountRepository.saveAndFlush(created);
            } catch (DataIntegrityViolationException exception) {
                // Two registrations for the same email at once: the unique constraint decides.
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
            }
        });

        String channelName = request.channelName() == null || request.channelName().isBlank()
                ? displayName(account) : request.channelName().trim();
        return withNewChannel(account, channelName);
    }

    /**
     * Sign-in through an external identity provider (Cognito). The provider's user id is linked to an ITube account
     * the first time: the account with the same email if there is one, otherwise a new account (without a password)
     * and channel. Only verified emails are trusted, otherwise anyone could take over an account by signing up at the
     * provider with someone else's address.
     */
    public AuthResponse externalSignIn(ExternalProfile profile) {
        ExternalIdentityEntity.Key key = new ExternalIdentityEntity.Key(profile.provider(), profile.subject());
        Optional<AccountEntity> linked = externalIdentities.findById(key).flatMap(link -> accountRepository.findById(link.getUserId()));
        if (linked.isPresent()) return withPrimaryChannel(linked.get());
        if (!profile.emailVerified() || profile.email() == null || profile.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verify your email address first");
        }
        String email = normaliseEmail(profile.email());
        AccountEntity[] created = new AccountEntity[1];
        AccountEntity account;
        try {
            account = transactions.execute(status -> {
                AccountEntity existing = accountRepository.findEntityByEmail(email).orElseGet(() -> {
                    AccountEntity fresh = new AccountEntity();
                    fresh.setFirstname(limit(profile.firstname(), 100));
                    fresh.setLastname(limit(profile.lastname(), 100));
                    fresh.setEmail(email);
                    created[0] = accountRepository.saveAndFlush(fresh);
                    return created[0];
                });
                externalIdentities.saveAndFlush(new ExternalIdentityEntity(key, existing.getUserId(), email,
                        OffsetDateTime.now(clock)));
                return existing;
            });
        } catch (DataIntegrityViolationException race) {
            // The same person signing in twice at once: the other request linked (or created) the account
            account = externalIdentities.findById(key).flatMap(link -> accountRepository.findById(link.getUserId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Try signing in again"));
            return withPrimaryChannel(account);
        }
        log.info("Linked {} user to ITube user {}{}", profile.provider(), account.getUserId(),
                created[0] != null ? " (new account)" : "");
        return created[0] != null ? withNewChannel(account, displayName(account)) : withPrimaryChannel(account);
    }

    /** What an identity provider tells us about a user (from its verified ID token). */
    public record ExternalProfile(String provider, String subject, String email, boolean emailVerified,
                                  String firstname, String lastname) {
    }

    private AuthResponse withNewChannel(AccountEntity account, String channelName) {
        ChannelDto channel = null;
        try {
            channel = catalog.ensureChannel(account.getUserId(), channelName);
        } catch (DownstreamUnavailableException exception) {
            // Only now: publishing on every registration would race the direct call and could create two channels
            log.warn("Catalog unavailable while registering user {}; the channel will be created from account.created",
                    account.getUserId());
            events.accountCreated(account.getUserId(), channelName);
        }
        return toResponse(account, channel == null ? null : channel.channelId(), channelName);
    }

    private static String limit(String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    public AuthResponse login(LoginRequest request) {
        AccountEntity account = accountRepository.findEntityByEmail(normaliseEmail(request.email())).orElse(null);
        if (account == null) {
            passwordHasher.matches(request.password(), dummyHash);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_LOGIN);
        }
        if (account.getPasswordHash() == null) {
            // Cognito-only account: same timing and answer as a wrong password
            passwordHasher.matches(request.password(), dummyHash);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_LOGIN);
        }
        if (!passwordHasher.matches(request.password(), account.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_LOGIN);
        }
        return withPrimaryChannel(account);
    }

    public AuthResponse currentUser(Long userId) {
        return accountRepository.findById(userId)
                .map(this::withPrimaryChannel)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in"));
    }

    /** Signing in must not depend on the catalog: if it's down, the user is signed in without channel details. */
    private AuthResponse withPrimaryChannel(AccountEntity account) {
        ChannelDto primary = null;
        try {
            List<ChannelDto> channels = catalog.channelsOwnedBy(account.getUserId());
            primary = channels.isEmpty() ? null : channels.get(0);
        } catch (DownstreamUnavailableException exception) {
            log.warn("Catalog unavailable; signing in user {} without channel details", account.getUserId());
        }
        return toResponse(account, primary == null ? null : primary.channelId(),
                primary == null ? null : primary.channelName());
    }

    private static AuthResponse toResponse(AccountEntity account, Long channelId, String channelName) {
        return new AuthResponse(account.getUserId(), account.getFirstname(), account.getLastname(),
                account.getEmail(), channelId, channelName);
    }
}
