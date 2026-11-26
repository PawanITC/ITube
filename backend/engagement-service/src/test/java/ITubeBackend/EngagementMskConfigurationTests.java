package ITubeBackend;

import ITubeBackend.common.ItubeDefaults;
import ITubeBackend.common.events.ItubeTopics;
import ITubeBackend.config.EngagementTopics;
import ITubeBackend.engagement.EngagementVideoCleanup;
import ITubeBackend.engagement.commands.EngagementCommandListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.annotation.KafkaListener;

import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.LoginContext;
import javax.security.auth.Subject;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Loads the actual profile files without starting Kafka, Scylla, an AWS client or a web server. */
class EngagementMskConfigurationTests {
    private static final String BOOTSTRAP = "b-1.example.abc.c1.kafka.eu-west-2.amazonaws.com:9098";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Match Boot's startup order: config data first, shared defaults at the lowest precedence afterward.
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withInitializer(context -> new ItubeDefaults().postProcessEnvironment(context.getEnvironment(), null))
            .withPropertyValues("spring.config.location=classpath:/application.properties")
            .withUserConfiguration(Binding.class, EngagementTopics.class);

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({KafkaProperties.class, ItubeTopics.class})
    static class Binding { }

    @Test void localKafkaKeepsItsPlaintextConfiguration() {
        runner.withPropertyValues("KAFKA_BOOTSTRAP_SERVERS=localhost:9092").run(context -> {
            assertThat(context).hasNotFailed();
            KafkaProperties kafka = context.getBean(KafkaProperties.class);
            for (Map<String, Object> config : clients(kafka)) {
                assertThat(config.get("bootstrap.servers")).isEqualTo(List.of("localhost:9092"));
                assertThat(config.getOrDefault("security.protocol", "PLAINTEXT")).isEqualTo("PLAINTEXT");
                assertThat(config).doesNotContainKeys("sasl.mechanism", "sasl.jaas.config");
            }
            assertThat(kafka.getAdmin().isFailFast()).isFalse();
            assertThat(context.getBean(ItubeTopics.class).replicas()).isEqualTo(1);
            assertThat(context.getBeansOfType(org.apache.kafka.clients.admin.NewTopic.class).values())
                    .hasSize(3).allSatisfy(topic -> assertThat(topic.replicationFactor()).isEqualTo((short) 1));
        });
    }

    @Test void mskProfileSecuresProducerBothConsumersAndAdmin() {
        runner.withPropertyValues("spring.profiles.active=msk", "KAFKA_BOOTSTRAP_SERVERS=" + BOOTSTRAP)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KafkaProperties kafka = context.getBean(KafkaProperties.class);
                    for (Map<String, Object> config : clients(kafka)) {
                        assertThat(config)
                                .containsEntry("bootstrap.servers", List.of(BOOTSTRAP))
                                .containsEntry("security.protocol", "SASL_SSL")
                                .containsEntry("sasl.mechanism", "AWS_MSK_IAM")
                                .containsEntry("sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;")
                                .containsEntry("sasl.client.callback.handler.class", "software.amazon.msk.auth.iam.IAMClientCallbackHandler")
                                .containsEntry("ssl.endpoint.identification.algorithm", "https")
                                .containsEntry("reconnect.backoff.ms", "1000")
                                .containsEntry("reconnect.backoff.max.ms", "10000");
                    }
                    assertThat(kafka.getAdmin().isFailFast()).isTrue();
                    assertThat(kafka.buildProducerProperties()).containsEntry("acks", "all")
                            .containsEntry("enable.idempotence", "true").containsEntry("max.block.ms", "5000");
                    assertThat(kafka.buildConsumerProperties()).containsEntry("auto.offset.reset", "earliest");
                    ItubeTopics topics = context.getBean(ItubeTopics.class);
                    assertThat(context.getEnvironment().getProperty("itube.topics.replicas"))
                            .as("MSK profile replication property").isEqualTo("2");
                    assertThat(topics.videoDeleted()).isEqualTo("video.deleted");
                    assertThat(topics.partitions()).isEqualTo(6);
                    assertThat(topics.replicas()).isEqualTo(2);
                    assertThat(context.getBeansOfType(org.apache.kafka.clients.admin.NewTopic.class).values())
                            .hasSize(3).allSatisfy(topic -> assertThat(topic.replicationFactor()).isEqualTo((short) 2));
                    assertThat(context.getBean("engagementCommandsTopic", org.apache.kafka.clients.admin.NewTopic.class)
                            .configs()).containsEntry("min.insync.replicas", "2");
                    assertThat(context.getEnvironment().getProperty("itube.engagement.kafka.topic"))
                            .isEqualTo("engagement.commands.v1");
                });
    }

    @Test void existingConsumerGroupsRemainCompatibleWithTheDedicatedRole() throws Exception {
        assertThat(EngagementCommandListener.class.getMethod("consume", org.apache.kafka.clients.consumer.ConsumerRecord.class)
                .getAnnotation(KafkaListener.class).groupId()).isEqualTo("itube-engagement-commands-v1");
        assertThat(EngagementVideoCleanup.class.getMethod("onVideoDeleted", String.class)
                .getAnnotation(KafkaListener.class).groupId()).isEqualTo("itube-engagement-cleanup");
    }

    @Test void runtimeAuthenticationPluginCanInitializeWithoutFetchingAwsCredentials() {
        // JAAS login initializes the plugin/provider chain; credentials are fetched only on authentication.
        // This catches incompatible runtime dependencies without contacting AWS or requiring a broker.
        assertThatCode(() -> {
            Class.forName("software.amazon.msk.auth.iam.IAMClientCallbackHandler").getDeclaredConstructor().newInstance();
            var configuration = new javax.security.auth.login.Configuration() {
                @Override public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                    return new AppConfigurationEntry[]{new AppConfigurationEntry(
                            "software.amazon.msk.auth.iam.IAMLoginModule",
                            AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, Map.of())};
                }
            };
            LoginContext login = new LoginContext("msk", new Subject(), null, configuration);
            login.login();
            login.logout();
            // The SDK used by the plugin contains EKS Pod Identity token-file support (>= 2.21.30).
            Class.forName("software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider");
            Class.forName("software.amazon.awssdk.http.apache5.Apache5HttpClient");
        }).doesNotThrowAnyException();
    }

    private static List<Map<String, Object>> clients(KafkaProperties kafka) {
        return List.of(kafka.buildProducerProperties(), kafka.buildConsumerProperties(), kafka.buildAdminProperties());
    }
}
