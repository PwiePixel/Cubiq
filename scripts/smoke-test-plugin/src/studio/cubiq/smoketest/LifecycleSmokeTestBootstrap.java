package studio.cubiq.smoketest;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

/**
 * Slow bootstrapper of the smoke test plugin.
 */
public final class LifecycleSmokeTestBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(final BootstrapContext context) {
        StepDelay.waitInStep();
    }
}
