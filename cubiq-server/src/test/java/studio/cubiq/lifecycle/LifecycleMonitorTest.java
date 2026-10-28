package studio.cubiq.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleMonitorTest {

    private final FakeClock clock = new FakeClock();
    private final List<String> closed = new ArrayList<>();
    private boolean recordTimings = true;
    private final LifecycleMonitor monitor = new LifecycleMonitor(
        this.clock,
        phase -> this.recordTimings,
        (phase, durationNanos) -> this.closed.add(phase.description() + " after " + TimeUnit.NANOSECONDS.toMillis(durationNanos) + " ms")
    );

    private static PluginTiming timing(final String plugin, final PluginStep step, final long startNanos, final long millis) {
        return new PluginTiming(plugin, step, startNanos, TimeUnit.MILLISECONDS.toNanos(millis));
    }

    private LifecycleMonitor.OpenPhase onlyOpenPhase() {
        final List<LifecycleMonitor.OpenPhase> open = this.monitor.openPhases();
        assertEquals(1, open.size(), "open phases: " + open);
        return open.getFirst();
    }

    @Test
    void theInnermostOpenPhaseIsWatched() {
        try (LifecyclePhase startup = this.monitor.begin("Starting the server")) {
            assertSame(startup, this.onlyOpenPhase().phase());
            try (LifecyclePhase enable = this.monitor.beginPluginStep(PluginStep.ENABLE, "Example", "Example v1.0")) {
                assertSame(enable, this.onlyOpenPhase().phase());
                assertEquals("Enabling plugin Example v1.0", enable.description());
            }
            assertSame(startup, this.onlyOpenPhase().phase());
        }
        assertEquals(List.of(), this.monitor.openPhases());
    }

    @Test
    void openingAndClosingPhasesIsProgress() {
        try (LifecyclePhase _ = this.monitor.begin("Starting the server")) {
            final long started = this.onlyOpenPhase().lastProgressNanos();
            this.clock.advanceSeconds(5);
            this.monitor.beginPluginStep(PluginStep.LOAD, "Example", "Example v1.0").close();
            assertEquals(started + TimeUnit.SECONDS.toNanos(5), this.onlyOpenPhase().lastProgressNanos());
        }
    }

    @Test
    void aPhaseIsOnlyMarkedAsReportedWhileItsThreadMakesNoProgress() {
        try (LifecyclePhase startup = this.monitor.begin("Starting the server")) {
            final LifecycleMonitor.OpenPhase seen = this.onlyOpenPhase();
            this.clock.advanceMillis(10);
            this.monitor.beginPluginStep(PluginStep.LOAD, "Example", "Example v1.0").close();

            assertFalse(this.monitor.markReported(seen), "the thread made progress since");
            assertFalse(startup.reported);

            assertTrue(this.monitor.markReported(this.onlyOpenPhase()));
            assertTrue(startup.reported);
        }
    }

    @Test
    void pluginStepsAreTimed() {
        final long started = this.clock.getAsLong();
        final LifecyclePhase enable = this.monitor.beginPluginStep(PluginStep.ENABLE, "Example", "Example v1.0");
        this.clock.advanceMillis(1500);
        enable.close();

        assertEquals(List.of(timing("Example", PluginStep.ENABLE, started, 1500)), this.monitor.drainPluginTimings());
        assertEquals(List.of(), this.monitor.drainPluginTimings(), "draining removes the timings");
        assertEquals(List.of("Enabling plugin Example v1.0 after 1500 ms"), this.closed);
    }

    @Test
    void timeSpentInStepsOfOtherPluginsIsNotCounted() {
        // A plugin that enables another plugin from its onEnable
        final long started = this.clock.getAsLong();
        final LifecyclePhase outer = this.monitor.beginPluginStep(PluginStep.ENABLE, "Outer", "Outer v1.0");
        this.clock.advanceMillis(100);
        final LifecyclePhase inner = this.monitor.beginPluginStep(PluginStep.ENABLE, "Inner", "Inner v1.0");
        this.clock.advanceMillis(300);
        inner.close();
        this.clock.advanceMillis(200);
        outer.close();

        assertEquals(
            List.of(
                timing("Inner", PluginStep.ENABLE, started + TimeUnit.MILLISECONDS.toNanos(100), 300),
                timing("Outer", PluginStep.ENABLE, started, 300)
            ),
            this.monitor.drainPluginTimings()
        );
    }

    @Test
    void nestedPluginStepsAreNotCountedThroughOtherPhases() {
        final long started = this.clock.getAsLong();
        final LifecyclePhase outer = this.monitor.beginPluginStep(PluginStep.ENABLE, "Outer", "Outer v1.0");
        final LifecyclePhase server = this.monitor.begin("Something the server does for the plugin");
        final LifecyclePhase inner = this.monitor.beginPluginStep(PluginStep.LOAD, "Inner", "Inner v1.0");
        this.clock.advanceMillis(400);
        inner.close();
        this.clock.advanceMillis(100);
        server.close();
        outer.close();

        assertEquals(
            List.of(timing("Inner", PluginStep.LOAD, started, 400), timing("Outer", PluginStep.ENABLE, started, 100)),
            this.monitor.drainPluginTimings()
        );
    }

    @Test
    void closingAPhaseClosesThePhasesOpenedInsideIt() {
        final long started = this.clock.getAsLong();
        final LifecyclePhase startup = this.monitor.begin("Starting the server");
        final LifecyclePhase enable = this.monitor.beginPluginStep(PluginStep.ENABLE, "Example", "Example v1.0");
        this.clock.advanceMillis(50);

        startup.close();
        enable.close();

        assertEquals(List.of("Enabling plugin Example v1.0 after 50 ms", "Starting the server after 50 ms"), this.closed);
        assertEquals(List.of(), this.monitor.openPhases());
        assertEquals(List.of(timing("Example", PluginStep.ENABLE, started, 50)), this.monitor.drainPluginTimings());
    }

    @Test
    void closingAPhaseTwiceHasNoEffect() {
        final LifecyclePhase phase = this.monitor.begin("Stopping the server");
        phase.close();
        phase.close();

        assertEquals(List.of("Stopping the server after 0 ms"), this.closed);
    }

    @Test
    void closingAPhaseDoesNotAffectPhasesOpenedLater() {
        final LifecyclePhase first = this.monitor.begin("First");
        first.close();
        try (LifecyclePhase second = this.monitor.begin("Second")) {
            first.close();
            assertSame(second, this.onlyOpenPhase().phase());
        }
    }

    @Test
    void phasesCanBeClosedByAnotherThread() throws InterruptedException {
        final LifecyclePhase phase = this.monitor.begin("Starting the server");
        final Thread closer = new Thread(phase::close);
        closer.start();
        closer.join();

        assertEquals(List.of(), this.monitor.openPhases());
        assertEquals(List.of("Starting the server after 0 ms"), this.closed);
    }

    @Test
    void eachThreadHasItsOwnPhases() throws InterruptedException {
        final AtomicReference<LifecyclePhase> other = new AtomicReference<>();
        final Thread thread = new Thread(() -> other.set(this.monitor.begin("Bootstrapping the server")));
        thread.start();
        thread.join();

        try (LifecyclePhase mine = this.monitor.begin("Starting the server")) {
            final List<LifecycleMonitor.OpenPhase> open = this.monitor.openPhases();
            assertEquals(2, open.size());
            assertTrue(open.contains(new LifecycleMonitor.OpenPhase(thread, other.get(), other.get().startNanos())));
            assertTrue(open.contains(new LifecycleMonitor.OpenPhase(Thread.currentThread(), mine, mine.startNanos())));
        }
    }

    @Test
    void timingsAreOnlyRecordedWhenRequested() {
        this.recordTimings = false;
        this.monitor.beginPluginStep(PluginStep.DISABLE, "Example", "Example v1.0").close();

        assertEquals(List.of(), this.monitor.drainPluginTimings());
        assertEquals(1, this.closed.size(), "the listener is still notified");
    }

    @Test
    void forgottenThreadsAreNoLongerWatched() throws InterruptedException {
        final Thread thread = new Thread(() -> this.monitor.begin("Bootstrapping the server"));
        thread.start();
        thread.join();

        this.monitor.forget(thread);

        assertEquals(List.of(), this.monitor.openPhases());
        assertEquals(List.of(), this.closed, "phases of terminated threads are not reported as closed");
    }

    @Test
    void untrackedPhasesCanBeClosed() {
        LifecyclePhase.UNTRACKED.close();
        assertEquals(List.of(), this.closed);
    }
}
