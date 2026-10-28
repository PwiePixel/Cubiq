package studio.cubiq.lifecycle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How long the steps of starting or stopping the server took, collected while the server starts or stops.
 *
 * <p>Thread safety: steps are added by the threads that run them, for example the main thread and the server thread
 * during startup, so the methods are synchronized.</p>
 */
final class Breakdown {

    /**
     * The time of the steps with one label.
     *
     * @param label   the label of the steps, for example {@code worlds}
     * @param nanos   the time of the steps that did not run inside another step
     * @param details the time of the steps with a subject, for example of each world, in the order they ended
     */
    record Entry(String label, long nanos, List<Detail> details) {
    }

    /**
     * The time of the steps with one subject, for example of loading and preparing one world.
     *
     * @param subject the subject, for example the name of a world
     * @param nanos   the time
     */
    record Detail(String subject, long nanos) {
    }

    private static final class Collected {

        private long firstStartNanos;
        private long nanos;
        private final Map<String, Long> details = new LinkedHashMap<>();

        private Collected(final long firstStartNanos) {
            this.firstStartNanos = firstStartNanos;
        }
    }

    private final Map<String, Collected> entries = new LinkedHashMap<>();

    /**
     * Adds a step that has ended.
     *
     * @param step       the step
     * @param subject    what the step worked on, or {@code null}
     * @param enclosing  the step that the step ran inside, or {@code null}
     * @param startNanos when the step began
     * @param nanos      how long the step took
     */
    synchronized void add(final ServerStep step, final @Nullable String subject, final @Nullable ServerStep enclosing, final long startNanos, final long nanos) {
        if (enclosing != null && (subject == null || !enclosing.label().equals(step.label()))) {
            // Part of the time of the enclosing step
            return;
        }
        final Collected collected = this.entries.computeIfAbsent(step.label(), _ -> new Collected(startNanos));
        if (startNanos - collected.firstStartNanos < 0L) {
            collected.firstStartNanos = startNanos;
        }
        if (enclosing == null) {
            collected.nanos += nanos;
        }
        if (subject != null) {
            collected.details.merge(subject, nanos, Long::sum);
        }
    }

    /**
     * Returns the time of the steps, in the order they began.
     *
     * @return the entries
     */
    synchronized List<Entry> entries() {
        final List<Map.Entry<String, Collected>> sorted = new ArrayList<>(this.entries.entrySet());
        // Compared by difference, like the other times of the System.nanoTime() clock
        sorted.sort((first, second) -> Long.signum(first.getValue().firstStartNanos - second.getValue().firstStartNanos));
        final List<Entry> result = new ArrayList<>(sorted.size());
        for (final Map.Entry<String, Collected> entry : sorted) {
            final List<Detail> details = new ArrayList<>(entry.getValue().details.size());
            entry.getValue().details.forEach((subject, nanos) -> details.add(new Detail(subject, nanos)));
            result.add(new Entry(entry.getKey(), entry.getValue().nanos, List.copyOf(details)));
        }
        return List.copyOf(result);
    }
}
