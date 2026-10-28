package studio.cubiq.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Keeps track of the lifecycle phases that are open on each thread, and of how long the steps of each plugin take.
 *
 * <p>Thread safety: a phase is opened by the thread that runs it and is usually closed by that thread too. The
 * watchdog reads the open phases from its own thread. Everything about one thread is guarded by the lock of that
 * thread's {@link ThreadPhases} entry, and no other lock is taken while it is held. Listeners are called after the
 * lock has been released.</p>
 */
public final class LifecycleMonitor {

    /**
     * Notified when a phase has been closed.
     */
    @FunctionalInterface
    interface Listener {

        /**
         * Called after a phase has been closed, on the thread that closed it. Must not throw.
         *
         * @param phase         the closed phase
         * @param durationNanos how long the phase was open
         */
        void phaseClosed(LifecyclePhase phase, long durationNanos);
    }

    /**
     * The innermost open phase of a thread, as seen by the watchdog.
     *
     * @param thread            the thread
     * @param phase             its innermost open phase
     * @param lastProgressNanos when a phase of the thread was last opened or closed
     */
    record OpenPhase(Thread thread, LifecyclePhase phase, long lastProgressNanos) {
    }

    private static final class ThreadPhases {

        private final Thread thread;
        private @Nullable LifecyclePhase innermost;
        private long lastProgressNanos;
        /** Set when this entry has been removed from the map; a new entry must be created for the thread. */
        private boolean removed;

        private ThreadPhases(final Thread thread) {
            this.thread = thread;
        }
    }

    private record ClosedPhase(LifecyclePhase phase, long durationNanos, long exclusiveNanos) {
    }

    private final LongSupplier clock;
    private final BooleanSupplier recordPluginTimings;
    private final Listener listener;
    private final Map<Thread, ThreadPhases> threads = new ConcurrentHashMap<>();
    private final Queue<PluginTiming> timings = new ConcurrentLinkedQueue<>();

    /**
     * Creates a monitor.
     *
     * @param clock               the time source in nanoseconds, {@code System::nanoTime} outside of tests
     * @param recordPluginTimings whether the timing of plugin steps that end now should be kept for a summary
     * @param listener            notified when phases are closed
     */
    LifecycleMonitor(final LongSupplier clock, final BooleanSupplier recordPluginTimings, final Listener listener) {
        this.clock = clock;
        this.recordPluginTimings = recordPluginTimings;
        this.listener = listener;
    }

    /**
     * Opens a phase on the current thread.
     *
     * @param description what happens in the phase, for example {@code Starting the server}
     * @return the open phase
     */
    LifecyclePhase begin(final String description) {
        return this.begin(description, null, null, null, null);
    }

    /**
     * Opens a phase for a step of starting or stopping the server on the current thread.
     *
     * @param step    the step
     * @param subject what the step works on, for example the name of a world, or {@code null}
     * @return the open phase
     */
    LifecyclePhase beginServerStep(final ServerStep step, final @Nullable String subject) {
        return this.begin(step.describe(subject), null, null, step, subject);
    }

    /**
     * Opens a phase for a step of a plugin on the current thread.
     *
     * @param step              the step
     * @param pluginName        the name of the plugin, used in summaries
     * @param pluginDisplayName the name and version of the plugin, used in the description of the phase
     * @return the open phase
     */
    LifecyclePhase beginPluginStep(final PluginStep step, final String pluginName, final String pluginDisplayName) {
        return this.begin(step.describe(pluginDisplayName), step, pluginName, null, null);
    }

    private LifecyclePhase begin(
        final String description,
        final @Nullable PluginStep step,
        final @Nullable String pluginName,
        final @Nullable ServerStep serverStep,
        final @Nullable String subject
    ) {
        final Thread thread = Thread.currentThread();
        while (true) {
            final ThreadPhases phases = this.threads.computeIfAbsent(thread, ThreadPhases::new);
            synchronized (phases) {
                if (phases.removed) {
                    // Another thread closed the last phase of this entry and removed it in the meantime
                    continue;
                }
                final long now = this.clock.getAsLong();
                final LifecyclePhase phase = new LifecyclePhase(this, description, step, pluginName, serverStep, subject, thread, now, phases.innermost);
                phases.innermost = phase;
                phases.lastProgressNanos = now;
                return phase;
            }
        }
    }

    /**
     * Closes a phase and every phase that was opened inside it and is still open.
     *
     * @param phase the phase to close
     */
    void end(final LifecyclePhase phase) {
        final Thread thread = phase.thread();
        if (thread == null) {
            return;
        }
        final ThreadPhases phases = this.threads.get(thread);
        if (phases == null) {
            return;
        }
        final List<ClosedPhase> closed = new ArrayList<>(1);
        synchronized (phases) {
            if (phases.removed || phase.closed || !isOpenIn(phases, phase)) {
                return;
            }
            final long now = this.clock.getAsLong();
            LifecyclePhase current = phases.innermost;
            while (current != null) {
                current.closed = true;
                final long duration = Math.max(0L, now - current.startNanos());
                final LifecyclePhase parent = current.parent();
                if (parent != null) {
                    // Plugin time is attributed to the innermost plugin step, also through phases that are no plugin steps
                    parent.nestedPluginNanos += current.isPluginStep() ? duration : current.nestedPluginNanos;
                }
                closed.add(new ClosedPhase(current, duration, Math.max(0L, duration - current.nestedPluginNanos)));
                if (current == phase) {
                    break;
                }
                current = parent;
            }
            phases.innermost = phase.parent();
            phases.lastProgressNanos = now;
            if (phases.innermost == null) {
                phases.removed = true;
                this.threads.remove(thread, phases);
            }
        }
        final boolean record = this.recordPluginTimings.getAsBoolean();
        for (final ClosedPhase closedPhase : closed) {
            final LifecyclePhase closedOne = closedPhase.phase();
            final PluginStep step = closedOne.pluginStep();
            final String pluginName = closedOne.pluginName();
            if (record && step != null && pluginName != null) {
                this.timings.add(new PluginTiming(pluginName, step, closedOne.startNanos(), closedPhase.exclusiveNanos()));
            }
            this.listener.phaseClosed(closedOne, closedPhase.durationNanos());
        }
    }

    private static boolean isOpenIn(final ThreadPhases phases, final LifecyclePhase phase) {
        for (LifecyclePhase current = phases.innermost; current != null; current = current.parent()) {
            if (current == phase) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the innermost open phase of every thread that has one.
     *
     * @return the open phases
     */
    List<OpenPhase> openPhases() {
        final List<OpenPhase> open = new ArrayList<>();
        for (final ThreadPhases phases : this.threads.values()) {
            synchronized (phases) {
                final LifecyclePhase innermost = phases.innermost;
                if (!phases.removed && innermost != null) {
                    open.add(new OpenPhase(phases.thread, innermost, phases.lastProgressNanos));
                }
            }
        }
        return open;
    }

    /**
     * Marks the innermost phase of a thread as reported, unless the thread has made progress since the phase was
     * returned by {@link #openPhases()}. A phase that is marked while it is open gets a "finished" message when it is
     * closed, which the lock guarantees.
     *
     * @param open the innermost phase of a thread, as returned by {@link #openPhases()}
     * @return whether the phase was marked
     */
    boolean markReported(final OpenPhase open) {
        final ThreadPhases phases = this.threads.get(open.thread());
        if (phases == null) {
            return false;
        }
        synchronized (phases) {
            if (phases.removed || phases.innermost != open.phase() || phases.lastProgressNanos != open.lastProgressNanos()) {
                return false;
            }
            open.phase().reported = true;
            return true;
        }
    }

    /**
     * Stops tracking the phases of a thread that has terminated without closing them. They are marked as closed
     * without being reported to the listener or timed.
     *
     * @param thread the terminated thread
     */
    void forget(final Thread thread) {
        final ThreadPhases phases = this.threads.get(thread);
        if (phases == null) {
            return;
        }
        synchronized (phases) {
            for (LifecyclePhase current = phases.innermost; current != null; current = current.parent()) {
                current.closed = true;
            }
            phases.innermost = null;
            phases.removed = true;
            this.threads.remove(thread, phases);
        }
    }

    /**
     * Removes and returns the plugin timings recorded so far.
     *
     * @return the timings, in the order the steps ended
     */
    List<PluginTiming> drainPluginTimings() {
        final List<PluginTiming> drained = new ArrayList<>();
        for (PluginTiming timing = this.timings.poll(); timing != null; timing = this.timings.poll()) {
            drained.add(timing);
        }
        return drained;
    }

    long now() {
        return this.clock.getAsLong();
    }
}
