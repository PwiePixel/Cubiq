package studio.cubiq.testing;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Runs Cubiq's plain unit tests, which need neither Minecraft registries nor a server.
 *
 * <p>The server test task only executes classes named {@code *TestSuite}. Tests that need one of the
 * upstream test environments ({@code @Normal}, {@code @VanillaFeature}, ...) are excluded here and must
 * be run by a suite that configures that environment.</p>
 */
@Suite(failIfNoTests = false)
@SuiteDisplayName("Cubiq unit tests that need no registries or server")
@SelectPackages("studio.cubiq")
@ExcludeTags({"Normal", "VanillaFeature", "AllFeatures", "Legacy", "Slow"})
public class CubiqUnitTestSuite {
}
