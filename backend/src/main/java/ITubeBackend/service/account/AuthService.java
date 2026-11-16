package ITubeBackend.service.account;

import ITubeBackend.dto.accounts.AuthResponse;
import ITubeBackend.dto.accounts.LoginRequest;
import ITubeBackend.dto.accounts.RegisterRequest;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.entities.AccountEntity;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.repository.AccountRepository;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.security.PasswordHasher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
public class AuthService {

    private static final String INVALID_LOGIN = "Incorrect email or password";

    private final AccountRepository accountRepository;
    private final ChannelRepository channelRepository;
    private final PasswordHasher passwordHasher;
    // Compared against when the email is unknown, so a wrong email takes as long as a wrong password.
    private final String dummyHash;

    public AuthService(AccountRepository accountRepository, ChannelRepository channelRepository,
                       PasswordHasher passwordHasher) {
        this.accountRepository = accountRepository;
        this.channelRepository = channelRepository;
        this.passwordHasher = passwordHasher;
        this.dummyHash = passwordHasher.hash("itube-timing-equaliser");
    }

    public static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normaliseEmail(request.email());
        if (accountRepository.findEntityByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }

        AccountEntity account = new AccountEntity();
        account.setFirstname(request.firstname().trim());
        account.setLastname(request.lastname().trim());
        account.setEmail(email);
        account.setPasswordHash(passwordHasher.hash(request.password()));
        try {
            account = accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException exception) {
            // Two registrations for the same email at once: the unique constraint decides.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }

        String channelName = request.channelName() == null || request.channelName().isBlank()
                ? account.getFirstname() + " " + account.getLastname()
                : request.channelName().trim();
        ChannelEntity channel = new ChannelEntity();
        channel.setUser(account);
        channel.setChannelName(channelName);
        channel = channelRepository.save(channel);

        return toResponse(account, channel.getChannelId(), channelName);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        AccountEntity account = accountRepository.findEntityByEmail(normaliseEmail(request.email())).orElse(null);
        if (account == null) {
            passwordHasher.matches(request.password(), dummyHash);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_LOGIN);
        }
        if (!passwordHasher.matches(request.password(), account.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_LOGIN);
        }
        return withPrimaryChannel(account);
    }

    @Transactional(readOnly = true)
    public AuthResponse currentUser(Long userId) {
        return accountRepository.findById(userId)
                .map(this::withPrimaryChannel)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in"));
    }

    private AuthResponse withPrimaryChannel(AccountEntity account) {
        List<ChannelDto> channels = channelRepository.findDtosByUserId(account.getUserId());
        ChannelDto primary = channels.isEmpty() ? null : channels.get(0);
        return toResponse(account, primary == null ? null : primary.channelId(),
                primary == null ? null : primary.channelName());
    }

    private static AuthResponse toResponse(AccountEntity account, Long channelId, String channelName) {
        return new AuthResponse(account.getUserId(), account.getFirstname(), account.getLastname(),
                account.getEmail(), channelId, channelName);
    }
}
