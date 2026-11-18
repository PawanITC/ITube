package ITubeBackend.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Caffeine: in-process caches, one set per service instance. Each cache has its own size limit and TTL from
 * {@code itube.cache.specs.<name>} (Caffeine spec syntax), so a hot video's metadata is answered from memory in
 * nanoseconds instead of a network round trip to a database.
 * <p>
 * Why it scales: video traffic follows a power law (a small set of videos gets most views), so a few MB of cache
 * per pod absorbs most reads. Caffeine also loads a missing key only once even if 1,000 requests ask for it at the
 * same moment (no "thundering herd" on the database when a viral video's entry expires).
 * <p>
 * Hit/miss/eviction counts appear in /actuator/metrics/cache.gets (recordStats in the spec).
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheConfig.CacheSpecs.class)
public class CacheConfig {

    @ConfigurationProperties(prefix = "itube.cache")
    public record CacheSpecs(Map<String, String> specs) {
        public CacheSpecs {
            specs = specs == null ? Map.of() : Map.copyOf(specs);
        }
    }

    @Bean
    public CacheManager cacheManager(CacheSpecs cacheSpecs) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // Unknown cache names fail loudly instead of silently creating an unbounded cache
        manager.setCacheNames(java.util.List.of());
        cacheSpecs.specs().forEach((name, spec) -> manager.registerCustomCache(name, Caffeine.from(spec).build()));
        return manager;
    }

    /** The Caffeine cache behind a named Spring cache, for bulk lookups that @Cacheable can't express. */
    @SuppressWarnings("unchecked")
    public static <K, V> Cache<K, V> nativeCache(CacheManager cacheManager, String name) {
        org.springframework.cache.Cache cache = cacheManager.getCache(name);
        if (!(cache instanceof CaffeineCache caffeine)) {
            throw new IllegalStateException("No Caffeine cache named '" + name + "'; add itube.cache.specs." + name);
        }
        return (Cache<K, V>) (Cache<?, ?>) caffeine.getNativeCache();
    }
}
