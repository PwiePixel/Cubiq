package studio.cubiq.lifecycle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reports lifecycle phases that make no progress, together with the stack trace of the thread that runs them.
 *
 * <p>A thread makes progress whenever one of its phases is opened or closed. When a thread has made no progress
 * for the configured time, its innermost phase is reported, and reported again at a fixed interval for as long as
 * it stays stuck. The watchdog only reports: it never interrupts, stops or restarts anything.</p>
 *
 * <p>Thread safety: {@link #check()} is called by a single thread, the watchdog thread.</p>
 */
final class LifecycleWatchdog {

    static final String THREAD_NAME = "Cubiq Lifecycle Watchdog";

    private record Report(LifecyclePhase phase, long lastProgressNanos, long reportedNanos) {
    }

    private final LifecycleMonitor monitor;
    private final BooleanSupplier active;
    private final long warnAfterNanos;
    private final long warnEveryNanos;
    private final Function<Thread, ThreadDumps.Dump> threadDump;
    private final Consumer<String> sink;
    private final Map<Thread, Report> reports = new HashMap<>();

    /**
     * Creates a watchdog.
     *
     * @param monitor        the phases to watch
     * @param active         whether phases should be reported now: while the server starts, reloads or stops,
     *                       but not while it runs normally and the upstream watchdog watches its ticks
     * @param warnAfterNanos how long a thread may make no progress before it is reported
     * @param warnEveryNanos how often a thread that stays stuck is reported again
     * @param threadDump     formats the stack trace of a thread
     * @param sink           receives the reports
     */
    LifecycleWatchdog(
        final LifecycleMonitor monitor,
        final BooleanSupplier active,
        final long warnAfterNanos,
        final long warnEveryNanos,
        final Function<Thread, ThreadDumps.Dump> threadDump,
        final Consumer<String> sink
    ) {
        this.monitor = monitor;
        this.active = active;
        this.warnAfterNanos = warnAfterNanos;
        this.warnEveryNanos = warnEveryNanos;
        this.threadDump = threadDump;
        this.sink = sink;
    }

    /**
     * Checks all open phases once and reports those that have made no progress for too long.
     */
    void check() {
        final long now = this.monitor.now();
        final boolean active = this.active.getAsBoolean();
        final Set<Thread> watched = new HashSet<>();
        for (final LifecycleMonitor.OpenPhase open : this.monitor.openPhases()) {
            final Thread thread = open.thread();
            if (!thread.isAlive()) {
                this.monitor.forget(thread);
                continue;
            }
            watched.add(thread);
            if (!active) {
                continue;
            }
            final long idleNanos = now - open.lastProgressNanos();
            if (idleNanos < this.warnAfterNanos) {
                continue;
            }
            final Report previous = this.reports.get(thread);
            if (previous != null && previous.phase() == open.phase() && previous.lastProgressNanos() == open.lastProgressNanos()
                && now - previous.reportedNanos() < this.warnEveryNanos) {
                continue;
            }
            final ThreadDumps.Dump stackTrace = this.threadDump.apply(thread);
            // If the thread made progress while its stack trace was taken, the stack trace may already show the next
            // step, so the report is dropped
            if (!this.monitor.markReported(open)) {
                continue;
            }
            this.reports.put(thread, new Report(open.phase(), open.lastProgressNanos(), now));
            this.sink.accept(LifecycleMessages.stalled(open.phase(), idleNanos, now - open.phase().startNanos(), thread.getName(), stackTrace));
        }
        this.reports.keySet().retainAll(watched);
    }

    /**
     * Starts a daemon thread that calls {@link #check()} at a fixed interval until the JVM exits.
     *
     * @param intervalMillis the time between checks
     * @param onError        called with the first exception thrown by a check; later ones are ignored
     * @return the started thread
     */
    Thread start(final long intervalMillis, final Consumer<Throwable> onError) {
        final Thread thread = new Thread(() -> this.run(intervalMillis, onError), THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void run(final long intervalMillis, final Consumer<Throwable> onError) {
        boolean failed = false;
        while (true) {
            try {
                this.check();
            } catch (final RuntimeException | LinkageError ex) {
                // A bug in this diagnostic must never affect the server; report it once and keep watching
                if (!failed) {
                    failed = true;
                    onError.accept(ex);
                }
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (final InterruptedException ex) {
                return;
            }
        }
    }
}
