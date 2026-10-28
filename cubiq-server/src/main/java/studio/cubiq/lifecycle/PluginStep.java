package studio.cubiq.lifecycle;

/**
 * A step in the lifecycle of a plugin that Cubiq measures.
 */
public enum PluginStep {
    /** Running the plugin's {@code PluginBootstrap}, before the server is created. Only Paper plugins have one. */
    BOOTSTRAP("Bootstrapping", "bootstrap"),
    /** {@code Plugin#onLoad()}. */
    LOAD("Loading", "load"),
    /** Enabling the plugin: {@code Plugin#onEnable()} and the {@code PluginEnableEvent}. */
    ENABLE("Enabling", "enable"),
    /** Disabling the plugin: {@code Plugin#onDisable()} and removing its tasks, services and listeners. */
    DISABLE("Disabling", "disable");

    private final String verb;
    private final String noun;

    PluginStep(final String verb, final String noun) {
        this.verb = verb;
        this.noun = noun;
    }

    /**
     * Describes this step for one plugin, for example {@code Enabling plugin Example v1.0}.
     *
     * @param pluginDisplayName the name and version of the plugin
     * @return the description
     */
    String describe(final String pluginDisplayName) {
        return this.verb + " plugin " + pluginDisplayName;
    }

    /**
     * Returns the name of this step in summaries, for example {@code enable}.
     *
     * @return the name
     */
    String noun() {
        return this.noun;
    }
}
