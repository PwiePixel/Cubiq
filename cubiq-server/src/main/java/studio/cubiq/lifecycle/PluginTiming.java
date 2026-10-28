package studio.cubiq.lifecycle;

/**
 * How long one step of a plugin took.
 *
 * @param pluginName the name of the plugin
 * @param step       the step
 * @param nanos      the time spent in the step, without the time spent in steps of other plugins that ran inside it
 */
record PluginTiming(String pluginName, PluginStep step, long nanos) {
}
