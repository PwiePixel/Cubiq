package studio.cubiq.lifecycle;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LifecycleMessagesTest {

    private static PluginTiming timing(final String plugin, final PluginStep step, final long millis) {
        return new PluginTiming(plugin, step, TimeUnit.MILLISECONDS.toNanos(millis));
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
        assertEquals("Stopped the server in 3.5 s.", LifecycleMessages.shutdownSummary(TimeUnit.MILLISECONDS.toNanos(3500), List.of()));
        assertEquals(
            "Stopped the server in 6.0 s. Plugins took 1.2 s to stop. Plugin: Example 1.2 s.",
            LifecycleMessages.shutdownSummary(TimeUnit.SECONDS.toNanos(6), List.of(timing("Example", PluginStep.DISABLE, 1200)))
        );
    }

    @Test
    void finishedMessage() {
        final LifecyclePhase phase = new LifecyclePhase(null, "Disabling plugin Example v1.0", PluginStep.DISABLE, "Example", null, 0L, null);
        assertEquals(
            "Lifecycle watchdog: Disabling plugin Example v1.0 finished after 45.2 s.",
            LifecycleMessages.finished(phase, TimeUnit.MILLISECONDS.toNanos(45_200))
        );
    }
}
