package studio.cubiq.lifecycle;

import java.util.List;

/**
 * How long starting or stopping the server took.
 *
 * @param totalNanos how long it took altogether
 * @param steps      the steps of the breakdown, in the order they began
 * @param plugins    the steps of the plugins, in the order they ended
 */
record TimingReport(long totalNanos, List<Breakdown.Entry> steps, List<PluginTiming> plugins) {

    /**
     * Returns the time that no step of the breakdown accounts for.
     *
     * @return the time in nanoseconds, at least zero
     */
    long otherNanos() {
        long steps = 0L;
        for (final Breakdown.Entry entry : this.steps) {
            steps += entry.nanos();
        }
        return Math.max(0L, this.totalNanos - steps);
    }
}
