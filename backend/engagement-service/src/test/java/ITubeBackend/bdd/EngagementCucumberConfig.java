package ITubeBackend.bdd;

import ITubeBackend.common.client.IdentityClient;
import ITubeBackend.support.FakeCatalog;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The real engagement-service with in-memory stores; catalog is a controllable fake, identity a mock. */
@CucumberContextConfiguration
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Import(FakeCatalog.Config.class)
public class EngagementCucumberConfig {
    @MockitoBean
    IdentityClient identity;
}
