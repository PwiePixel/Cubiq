package studio.cubiq.watchdog;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * Measures how much CPU time the server thread uses while it does not tick, so that a watchdog report can tell a
 * thread that is busy, for example in an endless loop or a very long computation, from one that waits, for example
 * for a lock, a file, the network or a database.
 *
 * <p>The watchdog calls {@link #observe(long, Thread)} on every check. Whenever the server has ticked since the
 * previous check, the CPU time of the server thread at that moment becomes the new baseline, so a report covers the
 * time since (about) the last tick. Sampling costs one {@link ThreadMXBean#getThreadCpuTime(long)} call per check,
 * on the watchdog thread; the server thread is not affected.</p>
 *
 * <p>Thread safety: used only by the watchdog thread. Its methods never throw, because an exception would end the
 * watchdog thread and with it the server's protection against hangs.</p>
 */
public final class ServerThreadCpu {

    /** At least this share of the time on the CPU counts as busy. */
    static final int BUSY_PERCENT = 80;
    /** At most this share of the time on the CPU counts as waiting. */
    static final int WAITING_PERCENT = 20;

    private final ToLongFunction<Thread> cpuTimeNanos;
    private final LongSupplier clockMillis;
    private long observedTick = Long.MIN_VALUE;
    private long baselineCpuNanos = -1L;
    private long baselineMillis;

    /**
     * Creates a tracker that uses the JVM's thread CPU time measurement.
     */
    public ServerThreadCpu() {
        this(ServerThreadCpu::threadCpuTimeNanos, () -> System.nanoTime() / 1_000_000L);
    }

    ServerThreadCpu(final ToLongFunction<Thread> cpuTimeNanos, final LongSupplier clockMillis) {
        this.cpuTimeNanos = cpuTimeNanos;
        this.clockMillis = clockMillis;
    }

    private static long threadCpuTimeNanos(final Thread thread) {
        try {
            final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            if (!threads.isThreadCpuTimeSupported() || !threads.isThreadCpuTimeEnabled()) {
                return -1L;
            }
            // -1 if the thread is not alive
            return threads.getThreadCpuTime(thread.threadId());
        } catch (final RuntimeException ex) {
            return -1L;
        }
    }

    /**
     * Called on every check of the watchdog.
     *
     * @param lastTick     when the server thread last ticked, as tracked by the watchdog
     * @param serverThread the server thread
     */
    public void observe(final long lastTick, final Thread serverThread) {
        if (lastTick != this.observedTick) {
            this.observedTick = lastTick;
            this.baselineCpuNanos = this.cpuTimeNanos.applyAsLong(serverThread);
            this.baselineMillis = this.clockMillis.getAsLong();
        }
    }

    /**
     * Describes how much CPU time the server thread used since the baseline.
     *
     * @param serverThread the server thread
     * @return one line for the watchdog report, or {@code null} if the CPU time cannot be measured
     */
    public @Nullable String describe(final Thread serverThread) {
        final long cpuNanos = this.cpuTimeNanos.applyAsLong(serverThread);
        final long elapsedMillis = this.clockMillis.getAsLong() - this.baselineMillis;
        if (cpuNanos < 0L || this.baselineCpuNanos < 0L || elapsedMillis <= 0L) {
            return null;
        }
        final long cpuMillis = Math.max(0L, (cpuNanos - this.baselineCpuNanos) / 1_000_000L);
        final long percent = Math.min(100L, cpuMillis * 100L / elapsedMillis);
        final String meaning;
        if (percent >= BUSY_PERCENT) {
            meaning = "it is busy, for example in an endless loop or a very long computation";
        } else if (percent <= WAITING_PERCENT) {
            meaning = "it is mostly waiting, for example for a lock, a file, the network or a database";
        } else {
            meaning = "it is partly busy and partly waiting";
        }
        return "The server thread used " + cpuMillis + " ms of CPU time in the last " + elapsedMillis + " ms (" + percent
            + "%), so " + meaning + ".";
    }
}
