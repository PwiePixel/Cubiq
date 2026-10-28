package studio.cubiq.lifecycle;

import org.jspecify.annotations.Nullable;

/**
 * A step of the server lifecycle that runs on one thread, for example starting the server or enabling a plugin.
 *
 * <p>Phases are opened by {@link LifecycleMonitor} and closed with {@link #close()}, usually with
 * try-with-resources on the thread that opened them. Closing a phase also closes the phases that were opened inside
 * it and are still open, for example because an exception skipped their {@code close()}. Closing a phase more than
 * once has no effect.</p>
 */
public final class LifecyclePhase implements AutoCloseable {

    /**
     * A phase that is not tracked. Returned instead of throwing when tracking fails, because a problem in a
     * diagnostic must never affect the code it observes.
     */
    static final LifecyclePhase UNTRACKED = new LifecyclePhase(null, "untracked", null, null, null, 0L, null);

    private final @Nullable LifecycleMonitor monitor;
    private final String description;
    private final @Nullable PluginStep pluginStep;
    private final @Nullable String pluginName;
    private final @Nullable Thread thread;
    private final long startNanos;
    private final @Nullable LifecyclePhase parent;

    // Guarded by the lock of the monitor's entry for this phase's thread
    long nestedPluginNanos;
    boolean closed;

    /** Whether the watchdog has reported this phase. Written by the watchdog thread. */
    volatile boolean reported;

    LifecyclePhase(
        final @Nullable LifecycleMonitor monitor,
        final String description,
        final @Nullable PluginStep pluginStep,
        final @Nullable String pluginName,
        final @Nullable Thread thread,
        final long startNanos,
        final @Nullable LifecyclePhase parent
    ) {
        this.monitor = monitor;
        this.description = description;
        this.pluginStep = pluginStep;
        this.pluginName = pluginName;
        this.thread = thread;
        this.startNanos = startNanos;
        this.parent = parent;
    }

    /**
     * Returns what happens in this phase, for example {@code Enabling plugin Example v1.0}.
     *
     * @return the description
     */
    public String description() {
        return this.description;
    }

    @Nullable PluginStep pluginStep() {
        return this.pluginStep;
    }

    @Nullable String pluginName() {
        return this.pluginName;
    }

    @Nullable Thread thread() {
        return this.thread;
    }

    long startNanos() {
        return this.startNanos;
    }

    @Nullable LifecyclePhase parent() {
        return this.parent;
    }

    boolean isPluginStep() {
        return this.pluginStep != null;
    }

    /**
     * Closes this phase and every phase that was opened inside it and is still open.
     */
    @Override
    public void close() {
        if (this.monitor != null) {
            this.monitor.end(this);
        }
    }

    @Override
    public String toString() {
        return this.description;
    }
}
