package ITubeBackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * payments-service: Stripe Checkout and webhooks, channel membership tiers, and the entitlement check
 * ("may this user play this members-only video?"). Owns the Postgres schema "payments".
 * Deliberately NOT sharded: low volume, and money needs one strongly consistent source of truth.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PaymentsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsServiceApplication.class, args);
    }
}
