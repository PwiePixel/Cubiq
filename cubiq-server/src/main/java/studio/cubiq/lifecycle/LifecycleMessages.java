package studio.cubiq.lifecycle;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Formats the messages of the lifecycle diagnostics.
 */
final class LifecycleMessages {

    /** How many plugins a summary names. */
    static final int SLOWEST_PLUGINS = 5;

    private LifecycleMessages() {
    }

    /**
     * Formats the report about a phase that makes no progress.
     *
     * @param phase        the phase
     * @param idleNanos    how long its thread has made no progress
     * @param runningNanos how long the phase has been open
     * @param threadName   the name of the thread that runs the phase
     * @param stackTrace   the formatted stack trace of that thread
     * @return the report
     */
    static String stalled(
        final LifecyclePhase phase, final long idleNanos, final long runningNanos, final String threadName, final ThreadDumps.Dump stackTrace
    ) {
        final StringBuilder out = new StringBuilder("Lifecycle watchdog: ").append(phase.description());
        if (idleNanos >= runningNanos) {
            out.append(" has not finished after ").append(seconds(runningNanos)).append('.');
        } else {
            out.append(" has made no progress for ").append(seconds(idleNanos))
                .append(" (running for ").append(seconds(runningNanos)).append(").");
        }
        if (stackTrace.state() == Thread.State.WAITING || stackTrace.state() == Thread.State.TIMED_WAITING) {
            // For example a plugin's onDisable that waits until its own thread has written a backlog to a database:
            // the stack trace stays the same while the other thread makes progress
            out.append("\nThe thread that runs it is waiting, possibly for work on another thread, for example a plugin saving its data,")
                .append(" so the step is not necessarily stuck if this message repeats with the same stack trace. Stack trace of thread \"")
                .append(threadName).append("\":\n");
        } else {
            out.append("\nIf this message repeats with the same stack trace, the step is probably stuck. Stack trace of thread \"")
                .append(threadName).append("\", which runs it:\n");
        }
        out.append(stackTrace.text());
        // The logger adds the final line break
        if (out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * Formats the message that a phase which was reported as stuck has finished after all.
     *
     * @param phase         the phase
     * @param durationNanos how long it took
     * @return the message
     */
    static String finished(final LifecyclePhase phase, final long durationNanos) {
        return "Lifecycle watchdog: " + phase.description() + " finished after " + duration(durationNanos) + ".";
    }

    /**
     * Summarizes how long the server took to start and which steps took how long.
     *
     * @param timing how long the startup and its steps took
     * @return the summary, for example {@code Startup took 15.3 s: bootstrapping 4.1 s, worlds 7.9 s (world 3.0 s,
     *     world_nether 2.4 s, world_the_end 2.5 s), other 409 ms.}
     */
    static String startupBreakdown(final TimingReport timing) {
        final StringBuilder out = new StringBuilder("Startup took ").append(duration(timing.totalNanos()));
        appendBreakdown(out, timing);
        return out.toString();
    }

    /**
     * Summarizes how long the plugins took to start.
     *
     * @param timings the timings of the plugin steps during startup
     * @return the summary, or {@code null} if no plugin step was timed
     */
    static @Nullable String startupSummary(final List<PluginTiming> timings) {
        if (timings.isEmpty()) {
            return null;
        }
        final StringBuilder out = new StringBuilder("Plugins took ").append(duration(total(timings))).append(" to start (");
        final Map<PluginStep, Long> bySteps = new EnumMap<>(PluginStep.class);
        for (final PluginTiming timing : timings) {
            bySteps.merge(timing.step(), timing.nanos(), Long::sum);
        }
        final List<String> steps = new ArrayList<>();
        bySteps.forEach((step, nanos) -> steps.add(step.noun() + " " + duration(nanos)));
        out.append(String.join(", ", steps)).append("). ");
        appendSlowest(out, timings);
        return out.toString();
    }

    /**
     * Summarizes how long the server took to stop, which steps took how long, and how long the plugins took.
     *
     * @param timing how long the shutdown, its steps and the plugins took
     * @return the summary
     */
    static String shutdownSummary(final TimingReport timing) {
        final StringBuilder out = new StringBuilder("Stopped the server in ").append(duration(timing.totalNanos()));
        appendBreakdown(out, timing);
        if (!timing.plugins().isEmpty()) {
            out.append(" Plugins took ").append(duration(total(timing.plugins()))).append(" to stop. ");
            appendSlowest(out, timing.plugins());
        }
        return out.toString();
    }

    /**
     * Appends the steps of a breakdown with their details and the time no step accounts for, leaving out what took less
     * than half a millisecond, and ends the sentence.
     */
    private static void appendBreakdown(final StringBuilder out, final TimingReport timing) {
        final List<String> parts = new ArrayList<>();
        for (final Breakdown.Entry entry : timing.steps()) {
            if (isNegligible(entry.nanos())) {
                continue;
            }
            final StringBuilder part = new StringBuilder(entry.label()).append(' ').append(duration(entry.nanos()));
            final List<String> details = new ArrayList<>();
            for (final Breakdown.Detail detail : entry.details()) {
                if (!isNegligible(detail.nanos())) {
                    details.add(detail.subject() + " " + duration(detail.nanos()));
                }
            }
            if (!details.isEmpty()) {
                part.append(" (").append(String.join(", ", details)).append(')');
            }
            parts.add(part.toString());
        }
        if (!timing.steps().isEmpty() && !isNegligible(timing.otherNanos())) {
            parts.add("other " + duration(timing.otherNanos()));
        }
        if (!parts.isEmpty()) {
            out.append(": ").append(String.join(", ", parts));
        }
        out.append('.');
    }

    private static boolean isNegligible(final long nanos) {
        return Math.round(nanos / 1_000_000.0) == 0L;
    }

    private static long total(final List<PluginTiming> timings) {
        long total = 0L;
        for (final PluginTiming timing : timings) {
            total += timing.nanos();
        }
        return total;
    }

    private static void appendSlowest(final StringBuilder out, final List<PluginTiming> timings) {
        final Map<String, Long> byPlugin = new LinkedHashMap<>();
        for (final PluginTiming timing : timings) {
            byPlugin.merge(timing.pluginName(), timing.nanos(), Long::sum);
        }
        final List<Map.Entry<String, Long>> slowest = new ArrayList<>(byPlugin.entrySet());
        // Stable sort: plugins that took equally long keep the order in which they finished
        slowest.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        out.append(byPlugin.size() == 1 ? "Plugin: " : "Slowest plugins: ");
        final List<String> named = new ArrayList<>();
        for (int i = 0; i < slowest.size() && i < SLOWEST_PLUGINS; i++) {
            named.add(slowest.get(i).getKey() + " " + duration(slowest.get(i).getValue()));
        }
        out.append(String.join(", ", named));
        if (slowest.size() > SLOWEST_PLUGINS) {
            out.append(" (and ").append(slowest.size() - SLOWEST_PLUGINS).append(" more)");
        }
        out.append('.');
    }

    /**
     * Formats a duration: milliseconds below one second, otherwise seconds with one decimal.
     *
     * @param nanos the duration in nanoseconds
     * @return the formatted duration, for example {@code 312 ms} or {@code 4.2 s}
     */
    static String duration(final long nanos) {
        final long millis = Math.round(nanos / 1_000_000.0);
        if (millis < 1000L) {
            return millis + " ms";
        }
        return String.format(Locale.ROOT, "%.1f s", nanos / 1_000_000_000.0);
    }

    /**
     * Formats a duration in whole seconds, rounded down.
     *
     * @param nanos the duration in nanoseconds
     * @return the formatted duration, for example {@code 30 s}
     */
    static String seconds(final long nanos) {
        return TimeUnit.NANOSECONDS.toSeconds(nanos) + " s";
    }
}
