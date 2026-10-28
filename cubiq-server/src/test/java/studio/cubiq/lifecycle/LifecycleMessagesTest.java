package studio.cubiq.lifecycle;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LifecycleMessagesTest {

    private static PluginTiming timing(final String plugin, final PluginStep step, final long millis) {
        return new PluginTiming(plugin, step, 0L, TimeUnit.MILLISECONDS.toNanos(millis));
    }

    @Test
    void durationsBelowOneSecondAreShownInMilliseconds() {
        assertEquals("0 ms", LifecycleMessages.duration(0L));
        assertEquals("312 ms", LifecycleMessages.duration(312_400_000L));
        assertEquals("999 ms", LifecycleMessages.duration(999_400_000L));
        assertEquals("1.0 s", LifecycleMessages.duration(999_600_000L));
        assertEquals("4.2 s", LifecycleMessages.duration(4_249_000_000L));
        assertEquals("125.0 s", LifecycleMessages.duration(TimeUnit.SECONDS.toNanos(125)));
    }

    @Test
    void secondsAreRoundedDown() {
        assertEquals("30 s", LifecycleMessages.seconds(30_999_000_000L));
    }

    @Test
    void startupSummaryShowsTheStepsAndTheSlowestPlugins() {
        final String summary = LifecycleMessages.startupSummary(List.of(
            timing("WorldEdit", PluginStep.LOAD, 200),
            timing("LuckPerms", PluginStep.LOAD, 100),
            timing("Vault", PluginStep.ENABLE, 50),
            timing("WorldEdit", PluginStep.ENABLE, 700),
            timing("LuckPerms", PluginStep.ENABLE, 1020),
            timing("Example", PluginStep.BOOTSTRAP, 300)
        ));

        assertEquals(
            "Plugins took 2.4 s to start (bootstrap 300 ms, load 300 ms, enable 1.8 s). "
                + "Slowest plugins: LuckPerms 1.1 s, WorldEdit 900 ms, Example 300 ms, Vault 50 ms.",
            summary
        );
    }

    @Test
    void summariesNameAtMostFivePlugins() {
        final String summary = LifecycleMessages.startupSummary(List.of(
            timing("A", PluginStep.ENABLE, 600),
            timing("B", PluginStep.ENABLE, 500),
            timing("C", PluginStep.ENABLE, 400),
            timing("D", PluginStep.ENABLE, 300),
            timing("E", PluginStep.ENABLE, 200),
            timing("F", PluginStep.ENABLE, 100),
            timing("G", PluginStep.ENABLE, 100)
        ));

        assertEquals(
            "Plugins took 2.2 s to start (enable 2.2 s). Slowest plugins: A 600 ms, B 500 ms, C 400 ms, D 300 ms, E 200 ms (and 2 more).",
            summary
        );
    }

    @Test
    void thereIsNoStartupSummaryWithoutPlugins() {
        assertNull(LifecycleMessages.startupSummary(List.of()));
    }

    @Test
    void shutdownSummary() {
        assertEquals(
            "Stopped the server in 3.5 s.",
            LifecycleMessages.shutdownSummary(new TimingReport(TimeUnit.MILLISECONDS.toNanos(3500), List.of(), List.of()))
        );
        assertEquals(
            "Stopped the server in 6.0 s. Plugins took 1.2 s to stop. Plugin: Example 1.2 s.",
            LifecycleMessages.shutdownSummary(new TimingReport(TimeUnit.SECONDS.toNanos(6), List.of(), List.of(timing("Example", PluginStep.DISABLE, 1200))))
        );
        assertEquals(
            "Stopped the server in 6.0 s: disabling plugins 1.2 s, worlds 4.0 s (world 3.5 s, world_nether 500 ms), other 800 ms. "
                + "Plugins took 1.2 s to stop. Plugin: Example 1.2 s.",
            LifecycleMessages.shutdownSummary(new TimingReport(
                TimeUnit.SECONDS.toNanos(6),
                List.of(entry("disabling plugins", 1200), entry("worlds", 4000, detail("world", 3500), detail("world_nether", 500))),
                List.of(timing("Example", PluginStep.DISABLE, 1200))
            ))
        );
    }

    @Test
    void startupBreakdown() {
        assertEquals(
            "Startup took 12.0 s: bootstrapping 3.1 s, worlds 7.9 s (world 4.4 s, world_nether 3.5 s), other 1.0 s.",
            LifecycleMessages.startupBreakdown(new TimingReport(
                TimeUnit.SECONDS.toNanos(12),
                List.of(entry("bootstrapping", 3100), entry("worlds", 7900, detail("world", 4400), detail("world_nether", 3500))),
                List.of()
            ))
        );
    }

    @Test
    void theBreakdownLeavesOutWhatTookNoTime() {
        assertEquals(
            "Startup took 2.0 s: worlds 1.5 s (world 1.5 s), other 500 ms.",
            LifecycleMessages.startupBreakdown(new TimingReport(
                TimeUnit.SECONDS.toNanos(2),
                List.of(entry("delayed init tasks", 0), entry("worlds", 1500, detail("world", 1500), new Breakdown.Detail("world_the_end", 400_000L))),
                List.of()
            ))
        );
        assertEquals(
            "Startup took 1.0 s: worlds 1.0 s.",
            LifecycleMessages.startupBreakdown(new TimingReport(TimeUnit.SECONDS.toNanos(1), List.of(entry("worlds", 1000)), List.of())),
            "no time is left for other"
        );
        assertEquals(
            "Startup took 1.0 s: other 1.0 s.",
            LifecycleMessages.startupBreakdown(new TimingReport(TimeUnit.SECONDS.toNanos(1), List.of(entry("players", 0)), List.of())),
            "steps were measured, but took no time"
        );
    }

    private static Breakdown.Entry entry(final String label, final long millis, final Breakdown.Detail... details) {
        return new Breakdown.Entry(label, TimeUnit.MILLISECONDS.toNanos(millis), List.of(details));
    }

    private static Breakdown.Detail detail(final String subject, final long millis) {
        return new Breakdown.Detail(subject, TimeUnit.MILLISECONDS.toNanos(millis));
    }

    @Test
    void theReportOfARunningThreadSaysThatARepeatedStackTraceMeansStuck() {
        final LifecyclePhase phase = new LifecyclePhase(null, "Enabling plugin Example v1.0", PluginStep.ENABLE, "Example", null, null, null, 0L, null);

        final String report = LifecycleMessages.stalled(
            phase,
            TimeUnit.SECONDS.toNanos(30),
            TimeUnit.SECONDS.toNanos(30),
            "Server thread",
            new ThreadDumps.Dump(Thread.State.RUNNABLE, "\"Server thread\" #1 RUNNABLE\n")
        );

        assertEquals(
            "Lifecycle watchdog: Enabling plugin Example v1.0 has not finished after 30 s.\n"
                + "If this message repeats with the same stack trace, the step is probably stuck. Stack trace of thread \"Server thread\", which runs it:\n"
                + "\"Server thread\" #1 RUNNABLE",
            report
        );
    }

    @Test
    void theReportOfAWaitingThreadDoesNotCallTheStepStuck() {
        // For example a plugin whose onDisable waits until its own thread has written a backlog to a database
        final LifecyclePhase phase = new LifecyclePhase(null, "Disabling plugin Example v1.0", PluginStep.DISABLE, "Example", null, null, null, 0L, null);

        final String report = LifecycleMessages.stalled(
            phase,
            TimeUnit.SECONDS.toNanos(90),
            TimeUnit.SECONDS.toNanos(95),
            "Server thread",
            new ThreadDumps.Dump(Thread.State.TIMED_WAITING, "\"Server thread\" #1 TIMED_WAITING\n")
        );

        assertEquals(
            "Lifecycle watchdog: Disabling plugin Example v1.0 has made no progress for 90 s (running for 95 s).\n"
                + "The thread that runs it is waiting, possibly for work on another thread, for example a plugin saving its data,"
                + " so the step is not necessarily stuck if this message repeats with the same stack trace. Stack trace of thread \"Server thread\":\n"
                + "\"Server thread\" #1 TIMED_WAITING",
            report
        );
    }

    @Test
    void finishedMessage() {
        final LifecyclePhase phase = new LifecyclePhase(null, "Disabling plugin Example v1.0", PluginStep.DISABLE, "Example", null, null, null, 0L, null);
        assertEquals(
            "Lifecycle watchdog: Disabling plugin Example v1.0 finished after 45.2 s.",
            LifecycleMessages.finished(phase, TimeUnit.MILLISECONDS.toNanos(45_200))
        );
    }
}
