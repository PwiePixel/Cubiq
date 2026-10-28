package studio.cubiq.testing;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Runs Cubiq's tests that use the upstream {@code @Normal} test environment, which bootstraps Minecraft's
 * built-in registries and a dummy server without registry values.
 */
@Suite(failIfNoTests = false)
@SuiteDisplayName("Cubiq tests in the normal test environment")
@IncludeTags("Normal")
@SelectPackages("studio.cubiq")
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class CubiqNormalTestSuite {
}
