package ITubeBackend.common.client;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.RestClient;

/**
 * Typed clients for the other ITube services (base URLs from itube.services.*.url). They are lazy: a service only
 * builds the clients it actually uses. Tests replace them with @MockitoBean.
 */
@Configuration
@EnableConfigurationProperties(ResilienceProperties.class)
public class ServiceClientsConfig {

    @Bean
    InternalApi internalApi(@Value("${itube.internal.token:}") String token) {
        return new InternalApi(token);
    }

    @Bean
    FilterRegistrationBean<InternalApi.Guard> internalApiGuard(InternalApi internalApi) {
        FilterRegistrationBean<InternalApi.Guard> registration = new FilterRegistrationBean<>(new InternalApi.Guard(internalApi));
        registration.addUrlPatterns(InternalApi.PATH_PREFIX + "*");
        return registration;
    }

    @Bean
    DownstreamFactory downstreamFactory(RestClient.Builder restClientBuilder, ResilienceProperties properties,
                                        InternalApi internalApi, ObjectProvider<MeterRegistry> meterRegistry) {
        return new DownstreamFactory(restClientBuilder, properties, internalApi, meterRegistry);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    CatalogClient catalogClient(DownstreamFactory downstreams, CacheManager cacheManager,
                                @Value("${itube.services.catalog.url}") String url) {
        return new HttpCatalogClient(downstreams.create("catalog", url), cacheManager);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    IdentityClient identityClient(DownstreamFactory downstreams, CacheManager cacheManager,
                                  @Value("${itube.services.identity.url}") String url) {
        return new IdentityClient.Http(downstreams.create("identity", url), cacheManager);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    PaymentsClient paymentsClient(DownstreamFactory downstreams, CacheManager cacheManager,
                                  @Value("${itube.services.payments.url}") String url) {
        return new PaymentsClient.Http(downstreams.create("payments", url), cacheManager);
    }
}
