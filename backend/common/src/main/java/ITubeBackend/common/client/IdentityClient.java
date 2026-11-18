package ITubeBackend.common.client;

import ITubeBackend.common.cache.CacheConfig;
import ITubeBackend.dto.accounts.AccountDto;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Optional;

/** What other services may ask identity-service. Accounts are cached for 5 minutes (names rarely change). */
public interface IdentityClient {

    Optional<AccountDto> account(Long userId);

    class Http implements IdentityClient {
        private final Downstream identity;
        private final Cache<Long, AccountDto> accounts;

        public Http(Downstream identity, CacheManager cacheManager) {
            this.identity = identity;
            this.accounts = CacheConfig.nativeCache(cacheManager, "remote-account");
        }

        @Override
        public Optional<AccountDto> account(Long userId) {
            AccountDto cached = accounts.getIfPresent(userId);
            if (cached != null) return Optional.of(cached);
            Optional<AccountDto> loaded = identity.call(() -> HttpCatalogClient.notFoundAsEmpty(() -> identity.rest().get()
                    .uri("/internal/accounts/{id}", userId)
                    .retrieve()
                    .body(AccountDto.class)));
            loaded.ifPresent(value -> accounts.put(userId, value));
            return loaded;
        }
    }
}
