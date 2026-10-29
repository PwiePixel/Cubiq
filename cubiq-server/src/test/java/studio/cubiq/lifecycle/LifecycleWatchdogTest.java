package studio.cubiq.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleWatchdogTest {

    private static final long WARN_AFTER_SECONDS = 30;
    private static final long WARN_EVERY_SECONDS = 60;

    private final FakeClock clock = new FakeClock();
    private final LifecycleMonitor monitor = new LifecycleMonitor(this.clock, phase -> true, (phase, durationNanos) -> {
    });
    private final List<String> reports = new ArrayList<>();
    private boolean active = true;
    private Thread.State threadState = Thread.State.RUNNABLE;
    private Runnable whileTheStackTraceIsTaken = () -> {
    };
    private final LifecycleWatchdog watchdog = new LifecycleWatchdog(
        this.monitor,
        () -> this.active,
        TimeUnit.SECONDS.toNanos(WARN_AFTER_SECONDS),
        TimeUnit.SECONDS.toNanos(WARN_EVERY_SECONDS),
        thread -> {
            this.whileTheStackTraceIsTaken.run();
            return new ThreadDumps.Dump(this.threadState, "<stack trace of " + thread.getName() + ">");
        },
        this.reports::add
    );

    @Test
    void reportsAPhaseThatMakesNoProgress() {
        try (LifecyclePhase enable = this.monitor.beginPluginStep(PluginStep.ENABLE, "Example", "Example v1.0")) {
            this.clock.advanceSeconds(WARN_AFTER_SECONDS - 1);
            this.watchdog.check();
            assertEquals(List.of(), this.reports, "not before the limit");
            assertFalse(enable.reported);

            this.clock.advanceSeconds(1);
            this.watchdog.check();

            assertEquals(List.of(
                "Lifecycle watchdog: Enabling plugin Example v1.0 has not finished after 30 s.\n"
                    + "If this message repeats with the same stack trace, the step is probably stuck. Stack trace of thread \""
                    + Thread.currentThread().getName()
                    + "\", which runs it:\n<stack trace of " + Thread.currentThread().getName() + ">"
            ), this.reports);
            assertTrue(enable.reported);
        }
    }

    @Test
    void repeatsTheReportAfterTheInterval() {
        try (LifecyclePhase _ = this.monitor.begin("Stopping the server")) {
            this.clock.advanceSeconds(WARN_AFTER_SECONDS);
            this.watchdog.check();
            this.clock.advanceSeconds(WARN_EVERY_SECONDS - 1);
            this.watchdog.check();
            assertEquals(1, this.reports.size());

            this.clock.advanceSeconds(1);
            this.watchdog.check();

            assertEquals(2, this.reports.size());
            assertTrue(this.reports.get(1).startsWith("Lifecycle watchdog: Stopping the server has not finished after 90 s."), this.reports.get(1));
        }
    }

    @Test
    void progressRestartsTheCountdown() {
        try (LifecyclePhase _ = this.monitor.begin("Starting the server")) {
            this.clock.advanceSeconds(20);
            this.monitor.beginPluginStep(PluginStep.ENABLE, "Example", "Example v1.0").close();
            this.clock.advanceSeconds(20);
            this.watchdog.check();
            assertEquals(List.of(), this.reports, "20 s since the last progress");

            this.clock.advanceSeconds(10);
            this.watchdog.check();

            assertEquals(1, this.reports.size());
            assertTrue(
                this.reports.getFirst().startsWith("Lifecycle watchdog: Starting the server has made no progress for 30 s (running for 50 s)."),
                this.reports.getFirst()
            );
        }
    }

    @Test
    void aNewStuckPhaseIsReportedWithoutWaitingForTheInterval() {
        try (LifecyclePhase _ = this.monitor.begin("Starting the server")) {
            this.clock.advanceSeconds(WARN_AFTER_SECONDS);
            this.watchdog.check();
            try (LifecyclePhase _ = this.monitor.beginPluginStep(PluginStep.ENABLE, "Example", "Example v1.0")) {
                this.clock.advanceSeconds(WARN_AFTER_SECONDS);
                this.watchdog.check();
            }
        }

        assertEquals(2, this.reports.size());
        assertTrue(this.reports.get(1).startsWith("Lifecycle watchdog: Enabling plugin Example v1.0 has not finished"), this.reports.get(1));
    }

    @Test
    void theReportOfAWaitingThreadDoesNotCallTheStepStuck() {
        this.threadState = Thread.State.TIMED_WAITING;
        try (LifecyclePhase _ = this.monitor.beginPluginStep(PluginStep.DISABLE, "Example", "Example v1.0")) {
            this.clock.advanceSeconds(WARN_AFTER_SECONDS);
            this.watchdog.check();
        }

        assertEquals(1, this.reports.size());
        assertTrue(this.reports.getFirst().contains("\nThe thread that runs it is waiting, possibly for work on another thread"), this.reports.getFirst());
        assertFalse(this.reports.getFirst().contains("probably stuck"), this.reports.getFirst());
    }

    @Test
    void aStepThatEndsWhileTheStackTraceIsTakenIsNotReported() {
        final LifecyclePhase enable = this.monitor.beginPluginStep(PluginStep.ENABLE, "Example", "Example v1.0");
        this.clock.advanceSeconds(WARN_AFTER_SECONDS);
        // The stack trace would show what the thread does after the step
        this.whileTheStackTraceIsTaken = enable::close;

        this.watchdog.check();

        assertEquals(List.of(), this.reports);
        assertFalse(enable.reported, "no 'finished' message follows");
    }

    @Test
    void aThreadThatMakesProgressWhileTheStackTraceIsTakenIsNotReported() {
        try (LifecyclePhase startup = this.monitor.begin("Starting the server")) {
            this.clock.advanceSeconds(WARN_AFTER_SECONDS);
            this.whileTheStackTraceIsTaken = () -> {
                this.clock.advanceMillis(1);
                this.monitor.beginPluginStep(PluginStep.ENABLE, "Example", "Example v1.0").close();
            };

            this.watchdog.check();

            assertEquals(List.of(), this.reports);
            assertFalse(startup.reported);
        }
    }

    @Test
    void reportsNothingWhileInactive() {
        // While the server runs normally, the upstream watchdog watches it
        this.active = false;
        try (LifecyclePhase _ = this.monitor.beginPluginStep(PluginStep.ENABLE, "Example", "Example v1.0")) {
            this.clock.advanceSeconds(WARN_AFTER_SECONDS * 10);
            this.watchdog.check();
        }

        assertEquals(List.of(), this.reports);
    }

    @Test
    void forgetsThreadsThatHaveTerminated() throws InterruptedException {
        final Thread thread = new Thread(() -> this.monitor.begin("Bootstrapping the server"), "Terminated");
        thread.start();
        thread.join();
        this.clock.advanceSeconds(WARN_AFTER_SECONDS);

        this.watchdog.check();

        assertEquals(List.of(), this.reports);
        assertEquals(List.of(), this.monitor.openPhases());
    }
}
