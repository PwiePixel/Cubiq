package studio.cubiq.watchdog;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Formats thread dumps for the watchdog in the layout of Spigot's and Paper's watchdog, with the lock information
 * that their dumps leave out or mislabel.
 *
 * <p>Upstream captures the server thread without its locks, labels the monitors a thread holds as monitors it is
 * "waiting on", calls the thread id "PID" and never says which lock a thread waits for or which thread holds it.
 * When the server thread waits for a lock, the thread that holds it is usually the actual cause, so it is dumped
 * right after the server thread.</p>
 *
 * <p>The early warnings capture the server thread without the {@code java.util.concurrent} locks it holds: to find
 * them, HotSpot scans the whole heap while all threads are paused, which takes seconds on a large heap and would
 * make the lag worse every few seconds. The lock a thread waits for and the thread that holds it are known without
 * that scan. Upstream's full dump on a long timeout still captures them.</p>
 *
 * <p>The methods never throw, because they run on the watchdog thread, which must survive any problem in a report.
 * If the lock information cannot be captured, the plain stack trace is used.</p>
 */
public final class WatchdogThreadDump {

    static final String SEPARATOR = "------------------------------";

    private WatchdogThreadDump() {
    }

    /**
     * Dumps the server thread with its locks and, if it waits for a lock that another thread holds, that thread.
     *
     * @param serverThread the server thread
     * @return the lines to log
     */
    public static List<String> serverThread(final Thread serverThread) {
        try {
            return serverThreadWithLocks(serverThread);
        } catch (final RuntimeException | LinkageError ex) {
            final List<String> lines = new ArrayList<>();
            lines.add(SEPARATOR);
            lines.add("Current Thread: " + serverThread.getName() + " (locks unavailable: " + ex + ")");
            lines.add("\tStack:");
            for (final StackTraceElement frame : serverThread.getStackTrace()) {
                lines.add("\t\t" + frame);
            }
            return lines;
        }
    }

    private static List<String> serverThreadWithLocks(final Thread serverThread) {
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        final ThreadInfo info = threadInfo(threads, serverThread.threadId());
        final List<String> lines = new ArrayList<>();
        if (info == null) {
            lines.add(SEPARATOR);
            lines.add("Current Thread: " + serverThread.getName() + " (no longer running)");
            return lines;
        }
        lines.addAll(lines(info));
        final long ownerId = info.getLockOwnerId();
        if (ownerId != -1L && ownerId != info.getThreadId()) {
            final ThreadInfo owner = threadInfo(threads, ownerId);
            if (owner != null) {
                lines.add(SEPARATOR);
                lines.add("The server thread waits for a lock held by \"" + owner.getThreadName() + "\", which is likely the cause:");
                lines.addAll(lines(owner));
            }
        }
        return lines;
    }

    private static @Nullable ThreadInfo threadInfo(final ThreadMXBean threads, final long threadId) {
        // Without the locked synchronizers, which need the scan of the whole heap
        final ThreadInfo[] infos = threads.getThreadInfo(new long[] {threadId}, threads.isObjectMonitorUsageSupported(), false);
        return infos.length == 1 ? infos[0] : null;
    }

    /**
     * Formats one thread.
     *
     * @param thread the thread, captured with or without lock information
     * @return the lines to log
     */
    public static List<String> lines(final ThreadInfo thread) {
        try {
            return linesWithLocks(thread);
        } catch (final RuntimeException | LinkageError ex) {
            return List.of(SEPARATOR, "Current Thread: " + thread.getThreadName() + " (could not be formatted: " + ex + ")");
        }
    }

    private static List<String> linesWithLocks(final ThreadInfo thread) {
        final List<String> lines = new ArrayList<>();
        lines.add(SEPARATOR);
        lines.add("Current Thread: " + thread.getThreadName());
        lines.add("\tThread ID: " + thread.getThreadId()
            + " | Suspended: " + thread.isSuspended()
            + " | Native: " + thread.isInNative()
            + " | State: " + thread.getThreadState());
        final LockInfo lock = thread.getLockInfo();
        if (lock != null) {
            final String owner = thread.getLockOwnerName() == null
                ? ""
                : " held by \"" + thread.getLockOwnerName() + "\" (thread ID " + thread.getLockOwnerId() + ")";
            final String verb = thread.getThreadState() == Thread.State.BLOCKED ? "Blocked on" : "Waiting on";
            lines.add("\t" + verb + ": " + lock + owner);
        }
        final MonitorInfo[] monitors = thread.getLockedMonitors();
        if (monitors.length != 0) {
            lines.add("\tLocked monitors:");
            for (final MonitorInfo monitor : monitors) {
                lines.add("\t\t" + monitor + " locked at " + monitor.getLockedStackFrame());
            }
        }
        final LockInfo[] synchronizers = thread.getLockedSynchronizers();
        if (synchronizers.length != 0) {
            lines.add("\tLocked synchronizers:");
            for (final LockInfo synchronizer : synchronizers) {
                lines.add("\t\t" + synchronizer);
            }
        }
        lines.add("\tStack:");
        for (final StackTraceElement frame : thread.getStackTrace()) {
            lines.add("\t\t" + frame);
        }
        return lines;
    }
}
