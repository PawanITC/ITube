package ITubeBackend.e2e;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * End-to-end journeys against a running stack. Start everything first (dev.cmd, or a deployed environment), then:
 * <pre>./mvnw -pl e2e-tests test -Pe2e -De2e.baseUrl=http://localhost:8080</pre>
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "ITubeBackend.e2e")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, summary, html:target/e2e-report.html")
public class E2eTest {
}
