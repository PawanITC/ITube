package ITubeBackend.bdd;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.client.IdentityClient;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The real payments-service on an in-memory database (no Stripe key); catalog and identity are mocks. */
@CucumberContextConfiguration
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
public class PaymentsCucumberConfig {
    @MockitoBean
    CatalogClient catalog;

    @MockitoBean
    IdentityClient identity;
}
