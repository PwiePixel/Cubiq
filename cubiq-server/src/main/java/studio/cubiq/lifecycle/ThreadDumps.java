package studio.cubiq.lifecycle;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import org.jspecify.annotations.Nullable;

/**
 * Formats stack traces of live threads, including the monitors they hold and the lock they wait for, like
 * {@code jstack} does.
 *
 * <p>The {@code java.util.concurrent} locks that a thread holds are left out: to find them, HotSpot scans the whole
 * heap while all threads are paused, which takes seconds on a large heap. The lock that a thread waits for and the
 * thread that holds it are known without that scan.</p>
 */
final class ThreadDumps {

    /** Limits the size of a single dump, for example of a thread in a deep recursion. */
    static final int MAX_FRAMES = 200;

    /**
     * A formatted stack trace.
     *
     * @param state the state of the thread when the stack trace was taken
     * @param text  the formatted stack trace
     */
    record Dump(Thread.State state, String text) {
    }

    private ThreadDumps() {
    }

    /**
     * Formats the stack trace of a thread. If the thread is blocked on or waiting for a lock that another thread
     * holds, the stack trace of that thread follows, because it is usually the actual cause.
     *
     * @param thread the thread
     * @return the formatted stack trace
     */
    static Dump dump(final Thread thread) {
        return dump(ManagementFactory.getThreadMXBean(), thread);
    }

    /**
     * Like {@link #dump(Thread)}, with the given management interface instead of the JVM's.
     *
     * @param threads the management interface to capture the thread with
     * @param thread  the thread
     * @return the formatted stack trace
     */
    static Dump dump(final ThreadMXBean threads, final Thread thread) {
        final StringBuilder out = new StringBuilder();
        final ThreadInfo info = threadInfo(threads, thread.threadId());
        if (info == null) {
            // Not available from the management interface, for example for a virtual thread
            final Thread.State state = thread.getState();
            appendThread(out, thread.getName(), thread.threadId(), state, thread.getStackTrace());
            return new Dump(state, out.toString());
        }
        append(out, info);
        final long ownerId = info.getLockOwnerId();
        if (ownerId != -1L && ownerId != info.getThreadId()) {
            final ThreadInfo owner = threadInfo(threads, ownerId);
            if (owner != null) {
                out.append("The lock is held by \"").append(owner.getThreadName()).append("\":\n");
                append(out, owner);
            }
        }
        return new Dump(info.getThreadState(), out.toString());
    }

    private static @Nullable ThreadInfo threadInfo(final ThreadMXBean threads, final long threadId) {
        // Without the locked synchronizers, which need the scan of the whole heap
        final ThreadInfo[] infos = threads.getThreadInfo(new long[] {threadId}, threads.isObjectMonitorUsageSupported(), false);
        return infos.length == 1 ? infos[0] : null;
    }

    static void append(final StringBuilder out, final ThreadInfo info) {
        out.append('"').append(info.getThreadName()).append("\" #").append(info.getThreadId()).append(' ').append(info.getThreadState());
        if (info.getLockOwnerName() != null) {
            out.append(" on a lock held by \"").append(info.getLockOwnerName()).append("\" #").append(info.getLockOwnerId());
        }
        out.append('\n');
        final StackTraceElement[] stack = info.getStackTrace();
        final MonitorInfo[] lockedMonitors = info.getLockedMonitors();
        final int frames = Math.min(stack.length, MAX_FRAMES);
        for (int depth = 0; depth < frames; depth++) {
            out.append("\tat ").append(stack[depth]).append('\n');
            final LockInfo lock = info.getLockInfo();
            if (depth == 0 && lock != null) {
                switch (info.getThreadState()) {
                    case BLOCKED -> out.append("\t- blocked on ").append(lock).append('\n');
                    case WAITING, TIMED_WAITING -> out.append("\t- waiting on ").append(lock).append('\n');
                    default -> {
                    }
                }
            }
            for (final MonitorInfo monitor : lockedMonitors) {
                if (monitor.getLockedStackDepth() == depth) {
                    out.append("\t- locked ").append(monitor).append('\n');
                }
            }
        }
        appendOmitted(out, stack.length);
    }

    private static void appendThread(
        final StringBuilder out, final String name, final long id, final Thread.State state, final StackTraceElement[] stack
    ) {
        out.append('"').append(name).append("\" #").append(id).append(' ').append(state).append('\n');
        final int frames = Math.min(stack.length, MAX_FRAMES);
        for (int depth = 0; depth < frames; depth++) {
            out.append("\tat ").append(stack[depth]).append('\n');
        }
        appendOmitted(out, stack.length);
    }

    private static void appendOmitted(final StringBuilder out, final int frames) {
        if (frames > MAX_FRAMES) {
            out.append("\t... ").append(frames - MAX_FRAMES).append(" more\n");
        }
    }
}
