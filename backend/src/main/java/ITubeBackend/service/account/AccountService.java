package ITubeBackend.service.account;

import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.dto.accounts.AccountDto;
import ITubeBackend.dto.accounts.DevSessionDto;
import ITubeBackend.entities.AccountEntity;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.repository.AccountRepository;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.security.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private static final String DEV_EMAIL = "demo@itube.local";
    private static final String DEV_CHANNEL = "Demo Channel";
    private static final String DEV_VIEWER_EMAIL = "viewer@itube.local";
    private static final String DEV_VIEWER_CHANNEL = "Demo Viewer";

    private final AccountRepository accountRepository;
    private final ChannelRepository channelRepository;
    private final PasswordHasher passwordHasher;

    public AccountService(AccountRepository accountRepository, ChannelRepository channelRepository,
                          PasswordHasher passwordHasher) {
        this.accountRepository = accountRepository;
        this.channelRepository = channelRepository;
        this.passwordHasher = passwordHasher;
    }

    // DELETE LATER - UNTIL LOGIN IS COMPLETE
    @Transactional
    public DevSessionDto createDevSession() {
        return getOrCreateDevAccount(DEV_EMAIL, "Demo", "User", DEV_CHANNEL);
    }

    // DELETE LATER - UNTIL LOGIN IS COMPLETE. A fixed second user, so demos can switch between a
    // creator (createDevSession) and the same viewer without a private browser window.
    @Transactional
    public DevSessionDto createDevViewerSession() {
        return getOrCreateDevAccount(DEV_VIEWER_EMAIL, "Demo", "Viewer", DEV_VIEWER_CHANNEL);
    }

    private DevSessionDto getOrCreateDevAccount(String email, String firstname, String lastname, String channelName) {
        AccountEntity accountEntity = accountRepository.findEntityByEmail(email).orElseGet(() -> {
            AccountEntity newAccount = new AccountEntity();
            newAccount.setFirstname(firstname);
            newAccount.setLastname(lastname);
            newAccount.setEmail(email);
            newAccount.setPasswordHash(passwordHasher.hash("itube-dev-only-password"));
            return accountRepository.save(newAccount);
        });

        AccountDto account = accountRepository.findDtoByEmail(email).orElseThrow();
        ChannelDto channel = channelRepository.findDtoByOwnerAndName(account.userId(), channelName)
                .orElseGet(() -> {
                    ChannelEntity newChannel = new ChannelEntity();
                    newChannel.setUser(accountEntity);
                    newChannel.setChannelName(channelName);
                    channelRepository.save(newChannel);
                    return channelRepository.findDtoByOwnerAndName(account.userId(), channelName).orElseThrow();
                });

        return new DevSessionDto(account.userId(), account.email(), channel.channelId(), channel.channelName());
    }

    // REMOVE LATER - UNTIL LOGIN IS COMPLETE
    @Transactional
    public DevSessionDto createRandomDevSession() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = "dev-" + suffix + "@itube.local";
        AccountEntity accountEntity = new AccountEntity();
        accountEntity.setFirstname("Dev");
        accountEntity.setLastname(suffix);
        accountEntity.setEmail(email);
        accountEntity.setPasswordHash(passwordHasher.hash(UUID.randomUUID().toString()));
        accountEntity = accountRepository.save(accountEntity);

        String channelName = "Channel " + suffix;
        ChannelEntity channelEntity = new ChannelEntity();
        channelEntity.setUser(accountEntity);
        channelEntity.setChannelName(channelName);
        channelEntity = channelRepository.save(channelEntity);

        AccountDto account = accountRepository.findDtoByEmail(email).orElseThrow();
        return new DevSessionDto(account.userId(), account.email(),
                channelEntity.getChannelId(), channelName);
    }
}
