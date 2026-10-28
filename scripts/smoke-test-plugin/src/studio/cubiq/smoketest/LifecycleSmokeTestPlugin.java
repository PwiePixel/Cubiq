package studio.cubiq.smoketest;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * A plugin whose load, enable and disable steps are slow, for the smoke test of the lifecycle diagnostics.
 */
public final class LifecycleSmokeTestPlugin extends JavaPlugin {

    @Override
    public void onLoad() {
        StepDelay.waitInStep();
    }

    @Override
    public void onEnable() {
        StepDelay.waitInStep();
    }

    @Override
    public void onDisable() {
        StepDelay.waitInStep();
    }
}
