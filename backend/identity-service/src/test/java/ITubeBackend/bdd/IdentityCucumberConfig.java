package ITubeBackend.bdd;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.service.account.AccountEvents;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The real identity-service on an in-memory database; catalog-service and Kafka are mocks the steps control. */
@CucumberContextConfiguration
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
public class IdentityCucumberConfig {
    @MockitoBean
    CatalogClient catalog;

    @MockitoBean
    AccountEvents accountEvents;
}
