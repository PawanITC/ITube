package ITubeBackend.bdd;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/** Runs src/test/resources/features with `mvn test`. HTML report: target/cucumber-report.html */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "ITubeBackend.bdd")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "summary, html:target/cucumber-report.html")
public class CucumberTest {
}
