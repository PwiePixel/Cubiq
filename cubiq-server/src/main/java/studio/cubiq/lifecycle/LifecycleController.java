package studio.cubiq.lifecycle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The lifecycle of the server as seen by the lifecycle diagnostics: the state the server is in, the phases of
 * startup, reload and shutdown, the summaries logged when they end, the report written when they end, and the
 * watchdog.
 *
 * <p>Thread safety: the methods are called by the threads that start, reload and stop the server. These can differ,
 * for example the main thread and the server thread during startup, or the shutdown thread when a stuck server is
 * stopped by a signal. The state changes atomically; the open startup, reload and stop phases are guarded by
 * {@code this}. No lock of the monitor is held while this lock is taken.</p>
 */
final class LifecycleController {

    static final String WATCHDOG_PROPERTY = "cubiq.lifecycleWatchdog";
    static final String WARN_AFTER_PROPERTY = "cubiq.lifecycleWatchdog.warnAfter";
    static final String WARN_EVERY_PROPERTY = "cubiq.lifecycleWatchdog.warnEvery";
    static final String SUMMARY_PROPERTY = "cubiq.lifecycleSummary";
    static final String REPORT_PROPERTY = "cubiq.lifecycleReport";
    /** Paper's property that disables its watchdog, which is usually set while debugging. */
    static final String DISABLE_WATCHDOG_PROPERTY = "disable.watchdog";
    static final long DEFAULT_WARN_AFTER_SECONDS = 30L;
    static final long DEFAULT_WARN_EVERY_SECONDS = 60L;

    /** How often the watchdog checks the open phases. */
    static final long WATCHDOG_INTERVAL_MILLIS = 1000L;

    /**
     * What the server is doing. The watchdog reports in every state but {@link #RUNNING}, in which the upstream
     * watchdog watches the server's ticks.
     */
    enum State {
        STARTING,
        RUNNING,
        RELOADING,
        STOPPING
    }

    /**
     * The settings of the lifecycle diagnostics.
     *
     * @param summaries      whether to log how long starting and stopping the server and the plugins took
     * @param watchdog       whether to report phases that make no progress
     * @param warnAfterNanos how long a phase may make no progress before it is reported
     * @param warnEveryNanos how often a phase that stays stuck is reported again
     * @param report         the file to write how long starting and stopping the server took to, or {@code null}
     */
    record Settings(boolean summaries, boolean watchdog, long warnAfterNanos, long warnEveryNanos, @Nullable Path report) {

        static Settings fromSystemProperties() {
            final boolean watchdog = Boolean.parseBoolean(System.getProperty(WATCHDOG_PROPERTY, "true"))
                && !Boolean.getBoolean(DISABLE_WATCHDOG_PROPERTY);
            final long warnAfterSeconds = Math.max(1L, Long.getLong(WARN_AFTER_PROPERTY, DEFAULT_WARN_AFTER_SECONDS));
            final long warnEverySeconds = Math.max(1L, Long.getLong(WARN_EVERY_PROPERTY, DEFAULT_WARN_EVERY_SECONDS));
            final String report = System.getProperty(REPORT_PROPERTY, "").strip();
            return new Settings(
                Boolean.parseBoolean(System.getProperty(SUMMARY_PROPERTY, "true")),
                watchdog,
                TimeUnit.SECONDS.toNanos(warnAfterSeconds),
                TimeUnit.SECONDS.toNanos(warnEverySeconds),
                report.isEmpty() ? null : Path.of(report)
            );
        }
    }

    /**
     * Where the lifecycle diagnostics log to.
     */
    interface Log {

        void info(String message);

        void warn(String message);

        void warn(String message, Throwable cause);
    }

    private final Settings settings;
    private final Log log;
    private final LifecycleMonitor monitor;
    private final @Nullable LifecycleWatchdog watchdog;
    private final Consumer<LifecycleWatchdog> watchdogStarter;
    private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
    private final Breakdown startupSteps = new Breakdown();
    // Guarded by this
    private @Nullable LifecyclePhase startup;
    private @Nullable LifecyclePhase reload;
    private @Nullable LifecyclePhase stop;
    private @Nullable Breakdown shutdownSteps;
    private long shutdownStartNanos;
    private @Nullable TimingReport startupTiming;
    private boolean watchdogStarted;

    /**
     * Creates the controller of the server.
     *
     * @param settings   the settings
     * @param clock      the time source in nanoseconds
     * @param threadDump formats the stack trace of a thread
     * @param log        where to log to
     */
    LifecycleController(final Settings settings, final LongSupplier clock, final Function<Thread, ThreadDumps.Dump> threadDump, final Log log) {
        this(settings, clock, threadDump, log, watchdog -> watchdog.start(
            WATCHDOG_INTERVAL_MILLIS,
            ex -> log.warn("The lifecycle watchdog failed. It keeps running, but further errors are not logged", ex)
        ));
    }

    /**
     * Creates a controller whose watchdog is started by the given function, which tests use to run the watchdog's
     * checks themselves.
     */
    LifecycleController(
        final Settings settings,
        final LongSupplier clock,
        final Function<Thread, ThreadDumps.Dump> threadDump,
        final Log log,
        final Consumer<LifecycleWatchdog> watchdogStarter
    ) {
        this.settings = settings;
        this.log = log;
        this.watchdogStarter = watchdogStarter;
        this.monitor = new LifecycleMonitor(clock, () -> this.state.get() != State.RUNNING, this::phaseClosed);
        this.watchdog = settings.watchdog()
            ? new LifecycleWatchdog(this.monitor, () -> this.state.get() != State.RUNNING, settings.warnAfterNanos(), settings.warnEveryNanos(), threadDump, log::warn)
            : null;
    }

    State state() {
        return this.state.get();
    }

    LifecycleMonitor monitor() {
        return this.monitor;
    }

    @Nullable LifecycleWatchdog watchdog() {
        return this.watchdog;
    }

    /**
     * Opens the phase in which the plugins are discovered and Minecraft and the plugins are bootstrapped, before the
     * server is created.
     *
     * @return the open phase
     */
    LifecyclePhase bootstrapping() {
        this.startWatchdog();
        return this.monitor.beginServerStep(ServerStep.BOOTSTRAPPING, null);
    }

    LifecyclePhase serverStep(final ServerStep step, final @Nullable String subject) {
        return this.monitor.beginServerStep(step, subject);
    }

    LifecyclePhase pluginStep(final PluginStep step, final String pluginName, final String pluginDisplayName) {
        return this.monitor.beginPluginStep(step, pluginName, pluginDisplayName);
    }

    /**
     * Opens the phase that lasts until the server has started. Called by the server thread when it starts.
     */
    void startupBegins() {
        this.startWatchdog();
        synchronized (this) {
            if (this.state.get() != State.STARTING || this.startup != null) {
                return;
            }
        }
        final LifecyclePhase phase = this.monitor.begin("Starting the server");
        synchronized (this) {
            this.startup = phase;
        }
    }

    /**
     * Ends startup when the server has started, logs how long it and the plugins took, and writes the report.
     *
     * @param startupNanos how long the startup took, measured as the server reports it
     */
    void startupFinished(final long startupNanos) {
        final LifecyclePhase phase;
        synchronized (this) {
            if (this.state.get() != State.STARTING) {
                return;
            }
            phase = this.startup;
            this.startup = null;
        }
        if (phase != null) {
            // Closed before the state changes, so that steps it closes are still timed
            phase.close();
        }
        if (!this.state.compareAndSet(State.STARTING, State.RUNNING)) {
            // The server started to stop in the meantime
            return;
        }
        final List<PluginTiming> timings = this.monitor.drainPluginTimings();
        final TimingReport timing = new TimingReport(startupNanos, this.startupSteps.entries(), timings);
        synchronized (this) {
            this.startupTiming = timing;
        }
        if (this.settings.summaries()) {
            this.log.info(LifecycleMessages.startupBreakdown(timing));
            final String summary = LifecycleMessages.startupSummary(timings);
            if (summary != null) {
                this.log.info(summary);
            }
        }
        this.writeReport(timing, null);
    }

    /**
     * Opens the phase of a reload. The server runs normally again when it is closed.
     *
     * @return the open phase
     */
    LifecyclePhase reloading() {
        this.startWatchdog();
        // A reload while the server starts or stops, for example from a task that a plugin scheduled for the first
        // tick, is part of that phase: it neither changes the state nor discards the timings of that phase's summary
        final boolean reloadsRunningServer = this.state.compareAndSet(State.RUNNING, State.RELOADING);
        if (reloadsRunningServer) {
            // Timings of plugins enabled or disabled at runtime are not summarized
            this.monitor.drainPluginTimings();
        }
        final LifecyclePhase phase = this.monitor.begin("Reloading the server");
        if (reloadsRunningServer) {
            synchronized (this) {
                this.reload = phase;
            }
        }
        return phase;
    }

    /**
     * Opens the phase of the shutdown. Called once by the thread that stops the server.
     */
    void stopping() {
        this.startWatchdog();
        if (this.state.getAndSet(State.STOPPING) == State.STOPPING) {
            return;
        }
        LifecyclePhase abortedStartup;
        synchronized (this) {
            abortedStartup = this.startup;
            // If startup failed on this thread, it is over. On another thread it may still be running, and the
            // watchdog has to keep reporting it.
            if (abortedStartup != null && abortedStartup.thread() == Thread.currentThread()) {
                this.startup = null;
            } else {
                abortedStartup = null;
            }
        }
        if (abortedStartup != null) {
            abortedStartup.close();
        }
        // Timings from before the shutdown are not part of its summary
        this.monitor.drainPluginTimings();
        final LifecyclePhase phase = this.monitor.begin("Stopping the server");
        synchronized (this) {
            this.stop = phase;
            this.shutdownSteps = new Breakdown();
            this.shutdownStartNanos = phase.startNanos();
        }
    }

    /**
     * Ends the shutdown phase, logs how long it took and writes the report. Called before logging is shut down.
     */
    void stopped() {
        final LifecyclePhase phase;
        synchronized (this) {
            phase = this.stop;
            this.stop = null;
        }
        if (phase == null) {
            return;
        }
        final long durationNanos = Math.max(0L, this.monitor.now() - phase.startNanos());
        // Also closes the steps of the shutdown that are still open, which adds them to its breakdown
        phase.close();
        final Breakdown steps;
        final TimingReport startup;
        synchronized (this) {
            steps = this.shutdownSteps;
            this.shutdownSteps = null;
            startup = this.startupTiming;
        }
        // Only the plugins that the shutdown disabled. A step that began before, for example during a reload or a
        // plugin enabled at runtime that another thread's shutdown overtook, is not part of the shutdown.
        final List<PluginTiming> timings = this.monitor.drainPluginTimings().stream()
            .filter(timing -> timing.step() == PluginStep.DISABLE && timing.startNanos() - phase.startNanos() >= 0L)
            .toList();
        final TimingReport timing = new TimingReport(durationNanos, steps == null ? List.of() : steps.entries(), timings);
        if (this.settings.summaries()) {
            this.log.info(LifecycleMessages.shutdownSummary(timing));
        }
        this.writeReport(startup, timing);
    }

    private void phaseClosed(final LifecyclePhase phase, final long durationNanos) {
        if (phase.reported) {
            this.log.info(LifecycleMessages.finished(phase, durationNanos));
        }
        this.addToBreakdown(phase, durationNanos);
        final boolean reloadEnded;
        synchronized (this) {
            reloadEnded = phase == this.reload;
            if (reloadEnded) {
                this.reload = null;
            }
        }
        // If the server started to stop during the reload, the reload's timings are left to the shutdown summary,
        // which only takes the plugins disabled by the shutdown
        if (reloadEnded && this.state.compareAndSet(State.RELOADING, State.RUNNING)) {
            // Timings of the reload are not summarized
            this.monitor.drainPluginTimings();
        }
    }

    /**
     * Adds a step of starting or stopping the server to the breakdown of the startup or the shutdown. Steps while the
     * server runs or reloads, and steps of the shutdown that began before it, are not part of either.
     */
    private void addToBreakdown(final LifecyclePhase phase, final long durationNanos) {
        final ServerStep step = phase.serverStep();
        if (step == null) {
            return;
        }
        final Breakdown breakdown;
        synchronized (this) {
            breakdown = switch (this.state.get()) {
                case STARTING -> this.startupSteps;
                case STOPPING -> phase.startNanos() - this.shutdownStartNanos >= 0L ? this.shutdownSteps : null;
                case RUNNING, RELOADING -> null;
            };
        }
        if (breakdown == null) {
            return;
        }
        ServerStep enclosing = null;
        for (LifecyclePhase parent = phase.parent(); parent != null && enclosing == null; parent = parent.parent()) {
            enclosing = parent.serverStep();
        }
        breakdown.add(step, phase.subject(), enclosing, phase.startNanos(), durationNanos);
    }

    private void writeReport(final @Nullable TimingReport startup, final @Nullable TimingReport shutdown) {
        final Path report = this.settings.report();
        if (report == null) {
            return;
        }
        try {
            LifecycleReport.write(report, startup, shutdown);
        } catch (final IOException | RuntimeException ex) {
            this.log.warn("Could not write the lifecycle report to " + report, ex);
        }
    }

    private void startWatchdog() {
        final LifecycleWatchdog watchdog = this.watchdog;
        if (watchdog == null) {
            return;
        }
        synchronized (this) {
            if (this.watchdogStarted) {
                return;
            }
            this.watchdogStarted = true;
        }
        this.watchdogStarter.accept(watchdog);
    }
}
