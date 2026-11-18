package ITubeBackend.service.payments;

import ITubeBackend.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-user fixed-window limit on payment actions (Checkout, tier changes, portal), stored in Redis.
 * Fails open if Redis is unavailable: Stripe's own rate limits still apply.
 */
@Component
public class PaymentRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(PaymentRateLimiter.class);

    private final StringRedisTemplate redis;
    private final PaymentProperties.RateLimit settings;

    public PaymentRateLimiter(StringRedisTemplate redis, PaymentProperties properties) {
        this.redis = redis;
        this.settings = properties.rateLimit();
    }

    public void check(String action, long userId) {
        String key = "ratelimit:payments:" + action + ":" + userId;
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1) redis.expire(key, settings.window());
        } catch (DataAccessException exception) {
            log.warn("Rate limiter unavailable, allowing request: {}", exception.getMessage());
            return;
        }
        if (count != null && count > settings.maxRequests()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please wait a few minutes.");
        }
    }
}
