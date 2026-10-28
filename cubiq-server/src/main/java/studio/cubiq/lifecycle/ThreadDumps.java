package studio.cubiq.lifecycle;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import org.jspecify.annotations.Nullable;

/**
 * Formats stack traces of live threads, including the locks they hold and wait for, like {@code jstack} does.
 */
final class ThreadDumps {

    /** Limits the size of a single dump, for example of a thread in a deep recursion. */
    static final int MAX_FRAMES = 200;

    private ThreadDumps() {
    }

    /**
     * Formats the stack trace of a thread. If the thread is blocked on or waiting for a lock that another thread
     * holds, the stack trace of that thread follows, because it is usually the actual cause.
     *
     * @param thread the thread
     * @return the formatted stack trace
     */
    static String dump(final Thread thread) {
        final StringBuilder out = new StringBuilder();
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        final ThreadInfo info = threadInfo(threads, thread.threadId());
        if (info == null) {
            // Not available from the management interface, for example for a virtual thread
            appendThread(out, thread.getName(), thread.threadId(), thread.getState(), thread.getStackTrace());
            return out.toString();
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
        return out.toString();
    }

    private static @Nullable ThreadInfo threadInfo(final ThreadMXBean threads, final long threadId) {
        final ThreadInfo[] infos = threads.getThreadInfo(
            new long[] {threadId}, threads.isObjectMonitorUsageSupported(), threads.isSynchronizerUsageSupported()
        );
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
        final LockInfo[] synchronizers = info.getLockedSynchronizers();
        if (synchronizers.length > 0) {
            out.append("\tLocked synchronizers:\n");
            for (final LockInfo synchronizer : synchronizers) {
                out.append("\t- ").append(synchronizer).append('\n');
            }
        }
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
