package ITubeBackend.service.account;

import ITubeBackend.dto.ChannelDto;
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
        AccountEntity accountEntity = accountRepository.findEntityByEmail(DEV_EMAIL).orElseGet(() -> {
            AccountEntity newAccount = new AccountEntity();
            newAccount.setFirstname("Demo");
            newAccount.setLastname("User");
            newAccount.setEmail(DEV_EMAIL);
            newAccount.setPasswordHash(passwordHasher.hash("itube-dev-only-password"));
            return accountRepository.save(newAccount);
        });

        AccountDto account = accountRepository.findDtoByEmail(DEV_EMAIL).orElseThrow();
        ChannelDto channel = channelRepository.findDtoByOwnerAndName(account.userId(), DEV_CHANNEL)
                .orElseGet(() -> {
                    ChannelEntity newChannel = new ChannelEntity();
                    newChannel.setUser(accountEntity);
                    newChannel.setChannelName(DEV_CHANNEL);
                    channelRepository.save(newChannel);
                    return channelRepository.findDtoByOwnerAndName(account.userId(), DEV_CHANNEL).orElseThrow();
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
