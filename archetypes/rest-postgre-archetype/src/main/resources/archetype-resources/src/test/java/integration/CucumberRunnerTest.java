#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.integration;


import org.junit.platform.suite.api.*;
import static io.cucumber.junit.platform.engine.Constants.*;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(
        key = GLUE_PROPERTY_NAME,
        value = "${package}.integration")
@ConfigurationParameter(
        key = PLUGIN_PROPERTY_NAME,
        value = "pretty,summary," +
                "html:target/cucumber-report.html," +
                "json:target/cucumber-report.json")
public class CucumberRunnerTest {
}
