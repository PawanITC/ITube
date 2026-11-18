package ITubeBackend.common.client;

import ITubeBackend.common.cache.CacheConfig;
import ITubeBackend.dto.entitlement.EntitlementRequest;
import ITubeBackend.dto.entitlement.PlaybackDecision;
import ITubeBackend.dto.video.VideoAccessDto;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * What other services may ask payments-service: may this user play this members-only video?
 * <p>
 * Only "allowed" answers are cached (20 s): a viewer who just bought a membership must not keep seeing a cached
 * "membership required". If payments is down this throws (fail closed): paid content is never given away, while
 * public videos never call payments at all, so normal viewing keeps working during a payments outage.
 */
public interface PaymentsClient {

    PlaybackDecision decide(Long userId, VideoAccessDto video);

    class Http implements PaymentsClient {
        private final Downstream payments;
        private final Cache<String, PlaybackDecision> allowed;

        public Http(Downstream payments, CacheManager cacheManager) {
            this.payments = payments;
            this.allowed = CacheConfig.nativeCache(cacheManager, "remote-entitlement");
        }

        @Override
        public PlaybackDecision decide(Long userId, VideoAccessDto video) {
            String key = userId + ":" + video.channelId() + ":" + video.visibility() + ":" + video.requiredTierLevel();
            PlaybackDecision cached = allowed.getIfPresent(key);
            if (cached != null) return cached;
            PlaybackDecision decision = payments.call(() -> payments.rest().post()
                    .uri("/internal/entitlements/decide")
                    .body(new EntitlementRequest(userId, video))
                    .retrieve()
                    .body(PlaybackDecision.class));
            if (decision == null) throw new IllegalStateException("payments-service returned no decision");
            if (decision.allowed()) allowed.put(key, decision);
            return decision;
        }
    }
}
