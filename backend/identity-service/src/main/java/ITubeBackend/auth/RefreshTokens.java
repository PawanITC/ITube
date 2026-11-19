package ITubeBackend.auth;

import ITubeBackend.entities.RefreshTokenEntity;
import ITubeBackend.repository.RefreshTokenRepository;
import ITubeBackend.security.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Long-lived refresh tokens: random 256-bit values, stored only as SHA-256 hashes, rotated on every use.
 * <p>
 * Each sign-in starts a family. A refresh marks the presented token used and returns a new token in the same family.
 * If a used token comes back after {@link #REUSE_GRACE} it was copied (stolen cookie, leaked log...): the whole family
 * is revoked, which signs out both the thief and the user, who signs in again. The grace period covers two tabs
 * refreshing with the same cookie at the same moment.
 */
@Service
public class RefreshTokens {
    static final Duration REUSE_GRACE = Duration.ofSeconds(30);
    /** Used tokens are kept this long to detect reuse, then deleted so a family doesn't grow forever. */
    private static final Duration USED_RETENTION = Duration.ofDays(1);
    private static final Logger log = LoggerFactory.getLogger(RefreshTokens.class);

    private final RefreshTokenRepository repository;
    private final AuthProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokens(RefreshTokenRepository repository, AuthProperties properties, TransactionTemplate transactions,
                         Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record Rotation(Long userId, String token) {
    }

    /** A new family for a fresh sign-in. Returns the raw token for the cookie. */
    public String create(Long userId) {
        return transactions.execute(status -> save(userId, UUID.randomUUID()));
    }

    /** Exchanges a refresh token for a new one (401 when it is unknown, expired or revoked). */
    public Rotation rotate(String token) {
        String hash = hash(token);
        // null = the family was revoked; the revocation commits, then the caller gets 401
        Rotation rotation = transactions.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            RefreshTokenEntity current = repository.findById(hash).orElseThrow(RefreshTokens::invalid);
            if (!current.getExpiresAt().isAfter(now)) throw invalid();
            if (repository.markUsed(hash, now) == 0) {
                // Used before. Fine within the grace period (parallel tabs); otherwise a copy is being replayed.
                OffsetDateTime usedAt = repository.findById(hash).map(RefreshTokenEntity::getUsedAt).orElse(null);
                if (usedAt == null || usedAt.plus(REUSE_GRACE).isBefore(now)) {
                    log.warn("Refresh token reuse for user {}: revoking token family {}", current.getUserId(),
                            current.getFamilyId());
                    repository.deleteFamily(current.getFamilyId());
                    return null;
                }
            }
            repository.deleteUsedBefore(current.getFamilyId(), now.minus(USED_RETENTION));
            return new Rotation(current.getUserId(), save(current.getUserId(), current.getFamilyId()));
        });
        if (rotation == null) throw invalid();
        return rotation;
    }

    /** Signs this device out: revokes the token's family. Unknown tokens are ignored. */
    public void revoke(String token) {
        transactions.executeWithoutResult(status -> repository.findById(hash(token))
                .ifPresent(found -> repository.deleteFamily(found.getFamilyId())));
    }

    @Scheduled(fixedDelayString = "${itube.auth.refresh-token-cleanup-interval:1h}", initialDelayString = "5m")
    public void deleteExpired() {
        Integer deleted = transactions.execute(status -> repository.deleteExpired(OffsetDateTime.now(clock)));
        if (deleted != null && deleted > 0) log.info("Deleted {} expired refresh tokens", deleted);
    }

    private String save(Long userId, UUID familyId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime now = OffsetDateTime.now(clock);
        repository.save(new RefreshTokenEntity(hash(token), familyId, userId, now, now.plus(properties.getRefreshTokenTtl())));
        return token;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again");
    }
}
