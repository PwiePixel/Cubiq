package studio.cubiq.watchdog;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * Measures how much CPU time the server thread uses while it does not tick, so that a watchdog report can tell a
 * thread that is busy, for example in an endless loop or a very long computation, from one that waits, for example
 * for a lock, a file, the network or a database. A thread also gets no CPU time while garbage collection pauses all
 * threads, so the pause time of the garbage collector in the same time is measured as well.
 *
 * <p>The watchdog calls {@link #observe(long, Thread)} on every check. Whenever the server has ticked since the
 * previous check, the CPU time of the server thread and the pause time of the garbage collector at that moment become
 * the new baseline, so a report covers the time since (about) the last tick. Sampling costs one
 * {@link ThreadMXBean#getThreadCpuTime(long)} call and one {@link GarbageCollectorMXBean#getCollectionTime()} call per
 * collector per check, on the watchdog thread; the server thread is not affected.</p>
 *
 * <p>Thread safety: used only by the watchdog thread. Its methods never throw, not even an {@link OutOfMemoryError}
 * on a server that runs out of memory: anything thrown would end the watchdog thread and with it the server's
 * protection against hangs, while a failed measurement only leaves out one line of a report.</p>
 */
public final class ServerThreadCpu {

    /** At least this share of the time on the CPU counts as busy. */
    static final int BUSY_PERCENT = 80;
    /** At most this share of the time on the CPU counts as waiting. */
    static final int WAITING_PERCENT = 20;
    /** From this share of the time paused for garbage collection on, the report mentions the pauses. */
    static final int GC_MENTION_PERCENT = 10;
    /** At least this share of the time paused for garbage collection counts as stopped by it. */
    static final int GC_PERCENT = 50;

    private final ToLongFunction<Thread> cpuTimeNanos;
    private final LongSupplier gcPauseMillis;
    private final LongSupplier clockMillis;
    private long observedTick = Long.MIN_VALUE;
    private long baselineCpuNanos = -1L;
    private long baselineGcPauseMillis = -1L;
    private long baselineMillis;

    /**
     * Creates a tracker that uses the JVM's measurements of thread CPU time and garbage collection.
     */
    public ServerThreadCpu() {
        this(ServerThreadCpu::threadCpuTimeNanos, pauseTimeMillis(pauseCollectors()), () -> System.nanoTime() / 1_000_000L);
    }

    /**
     * Creates a tracker with the given measurements.
     *
     * @param cpuTimeNanos  the CPU time of a thread in nanoseconds, or a negative value if it cannot be measured
     * @param gcPauseMillis the total time in milliseconds for which garbage collection paused all threads, or a
     *                      negative value if it cannot be measured
     * @param clockMillis   a monotonic clock in milliseconds
     */
    ServerThreadCpu(final ToLongFunction<Thread> cpuTimeNanos, final LongSupplier gcPauseMillis, final LongSupplier clockMillis) {
        this.cpuTimeNanos = cpuTimeNanos;
        this.gcPauseMillis = gcPauseMillis;
        this.clockMillis = clockMillis;
    }

    private static long threadCpuTimeNanos(final Thread thread) {
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (!threads.isThreadCpuTimeSupported() || !threads.isThreadCpuTimeEnabled()) {
            return -1L;
        }
        // -1 if the thread is not alive
        return threads.getThreadCpuTime(thread.threadId());
    }

    private static List<GarbageCollectorMXBean> pauseCollectors() {
        try {
            // ZGC and Shenandoah count their concurrent cycles, during which the server keeps running, in collectors
            // of their own ("ZGC Minor Cycles", "Shenandoah Cycles"). Their pauses and the work of all other
            // collectors, G1's "G1 Concurrent GC" included, which counts only its Remark and Cleanup pauses, stop
            // every thread.
            return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .filter(collector -> !collector.getName().endsWith(" Cycles"))
                .toList();
        } catch (final RuntimeException | LinkageError ex) {
            return List.of();
        }
    }

    private static LongSupplier pauseTimeMillis(final List<GarbageCollectorMXBean> collectors) {
        return () -> {
            if (collectors.isEmpty()) {
                return -1L;
            }
            long total = 0L;
            for (final GarbageCollectorMXBean collector : collectors) {
                final long millis = collector.getCollectionTime();
                if (millis < 0L) {
                    // Not supported by this collector
                    return -1L;
                }
                total += millis;
            }
            return total;
        };
    }

    /**
     * Called on every check of the watchdog.
     *
     * @param lastTick     when the server thread last ticked, as tracked by the watchdog
     * @param serverThread the server thread
     */
    public void observe(final long lastTick, final Thread serverThread) {
        try {
            if (lastTick != this.observedTick) {
                this.observedTick = lastTick;
                this.baselineCpuNanos = this.cpuTimeNanos.applyAsLong(serverThread);
                this.baselineGcPauseMillis = this.gcPauseMillis.getAsLong();
                this.baselineMillis = this.clockMillis.getAsLong();
            }
        } catch (final Throwable ex) {
            // See the class documentation: without a baseline, the next report leaves out the CPU line
            this.baselineCpuNanos = -1L;
        }
    }

    /**
     * Describes how much CPU time the server thread used since the baseline, and for how long garbage collection
     * paused it.
     *
     * @param serverThread the server thread
     * @return one line for the watchdog report, or {@code null} if the CPU time cannot be measured
     */
    public @Nullable String describe(final Thread serverThread) {
        try {
            return this.describeSinceBaseline(serverThread);
        } catch (final Throwable ex) {
            // See the class documentation
            return null;
        }
    }

    private @Nullable String describeSinceBaseline(final Thread serverThread) {
        final long cpuNanos = this.cpuTimeNanos.applyAsLong(serverThread);
        final long elapsedMillis = this.clockMillis.getAsLong() - this.baselineMillis;
        if (cpuNanos < 0L || this.baselineCpuNanos < 0L || elapsedMillis <= 0L) {
            return null;
        }
        final long cpuMillis = Math.max(0L, (cpuNanos - this.baselineCpuNanos) / 1_000_000L);
        final long percent = Math.min(100L, cpuMillis * 100L / elapsedMillis);
        final long gcTotalMillis = this.baselineGcPauseMillis < 0L ? -1L : this.gcPauseMillis.getAsLong();
        final long gcMillis = gcTotalMillis < 0L ? 0L : Math.max(0L, gcTotalMillis - this.baselineGcPauseMillis);
        final long gcPercent = Math.min(100L, gcMillis * 100L / elapsedMillis);
        final StringBuilder out = new StringBuilder("The server thread used ").append(cpuMillis).append(" ms of CPU time in the last ")
            .append(elapsedMillis).append(" ms (").append(percent).append("%)");
        if (gcPercent >= GC_MENTION_PERCENT) {
            out.append(", and garbage collection paused all threads for ").append(gcMillis).append(" ms (").append(gcPercent).append("%)");
        }
        out.append(", so ");
        if (percent >= BUSY_PERCENT) {
            out.append("it is busy, for example in an endless loop or a very long computation");
        } else if (gcPercent >= GC_PERCENT) {
            out.append("it is mostly stopped by garbage collection, which happens for example when the memory of the server is nearly full");
        } else if (percent <= WAITING_PERCENT) {
            out.append("it is mostly waiting, for example for a lock, a file, the network or a database, or it gets too little CPU time from the operating system");
        } else {
            out.append("it is partly busy and partly waiting");
        }
        return out.append('.').toString();
    }
}
