package ITubeBackend.bdd;

import ITubeBackend.common.client.PaymentsClient;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * One Spring context for every catalog scenario: the real catalog-service with two in-memory H2 databases as
 * shards (profile h2). payments-service is another process, so it is replaced by a mock the steps control.
 */
@CucumberContextConfiguration
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
public class CatalogCucumberConfig {
    @MockitoBean
    PaymentsClient payments;
}
