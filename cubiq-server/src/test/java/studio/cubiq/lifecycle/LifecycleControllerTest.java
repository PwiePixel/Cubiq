package studio.cubiq.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleControllerTest {

    private static final LifecycleController.Settings SETTINGS = new LifecycleController.Settings(
        true, true, TimeUnit.SECONDS.toNanos(30), TimeUnit.SECONDS.toNanos(60)
    );

    private final FakeClock clock = new FakeClock();
    private final List<String> log = new ArrayList<>();
    private final List<LifecycleWatchdog> startedWatchdogs = new ArrayList<>();
    private final LifecycleController controller = this.controller(SETTINGS);

    private LifecycleController controller(final LifecycleController.Settings settings) {
        return new LifecycleController(settings, this.clock, thread -> new ThreadDumps.Dump(Thread.State.RUNNABLE, "<stack trace>"), new LifecycleController.Log() {
            @Override
            public void info(final String message) {
                LifecycleControllerTest.this.log.add("INFO " + message);
            }

            @Override
            public void warn(final String message) {
                LifecycleControllerTest.this.log.add("WARN " + message);
            }

            @Override
            public void warn(final String message, final Throwable cause) {
                LifecycleControllerTest.this.log.add("WARN " + message + ": " + cause);
            }
        }, this.startedWatchdogs::add);
    }

    private LifecycleWatchdog watchdog() {
        return Objects.requireNonNull(this.controller.watchdog());
    }

    private void runPluginStep(final PluginStep step, final String plugin, final long millis) {
        try (LifecyclePhase _ = this.controller.pluginStep(step, plugin, plugin + " v1.0")) {
            this.clock.advanceMillis(millis);
        }
    }

    @Test
    void logsHowLongThePluginsTookToStart() {
        try (LifecyclePhase _ = this.controller.bootstrapping()) {
            this.runPluginStep(PluginStep.BOOTSTRAP, "Example", 100);
        }
        this.controller.startupBegins();
        this.runPluginStep(PluginStep.LOAD, "Example", 200);
        this.runPluginStep(PluginStep.ENABLE, "Example", 1500);
        this.runPluginStep(PluginStep.ENABLE, "Other", 70);

        this.controller.startupFinished();

        assertEquals(LifecycleController.State.RUNNING, this.controller.state());
        assertEquals(
            List.of("INFO Plugins took 1.9 s to start (bootstrap 100 ms, load 200 ms, enable 1.6 s). Slowest plugins: Example 1.8 s, Other 70 ms."),
            this.log
        );
        assertEquals(List.of(), this.controller.monitor().openPhases(), "startup is over");
    }

    @Test
    void logsNoStartupSummaryWithoutPlugins() {
        this.controller.startupBegins();
        this.controller.startupFinished();

        assertEquals(List.of(), this.log);
    }

    @Test
    void logsHowLongTheServerTookToStop() {
        this.controller.startupBegins();
        this.controller.startupFinished();
        // Enabled at runtime, for example by a plugin manager: not part of any summary
        this.runPluginStep(PluginStep.ENABLE, "Runtime", 300);

        this.controller.stopping();
        this.clock.advanceMillis(500);
        this.runPluginStep(PluginStep.DISABLE, "Example", 1200);
        this.clock.advanceMillis(300);
        this.controller.stopped();

        assertEquals(List.of("INFO Stopped the server in 2.0 s. Plugins took 1.2 s to stop. Plugin: Example 1.2 s."), this.log);
        assertEquals(LifecycleController.State.STOPPING, this.controller.state());
        assertEquals(List.of(), this.controller.monitor().openPhases());
    }

    @Test
    void aReloadWhileTheServerStartsIsPartOfTheStartup() {
        this.controller.startupBegins();
        this.runPluginStep(PluginStep.ENABLE, "Example", 1500);
        // For example from a task that a plugin scheduled for the first tick, which runs before startup has finished
        try (LifecyclePhase _ = this.controller.reloading()) {
            assertEquals(LifecycleController.State.STARTING, this.controller.state());
            this.runPluginStep(PluginStep.DISABLE, "Example", 100);
            this.runPluginStep(PluginStep.ENABLE, "Example", 200);
        }
        assertEquals(LifecycleController.State.STARTING, this.controller.state());

        this.controller.startupFinished();

        assertEquals(List.of("INFO Plugins took 1.8 s to start (enable 1.7 s, disable 100 ms). Plugin: Example 1.8 s."), this.log);
    }

    @Test
    void aShutdownDuringAReloadSummarizesOnlyThePluginsTheShutdownDisabled() throws InterruptedException {
        this.controller.startupBegins();
        this.controller.startupFinished();
        final LifecyclePhase reload = this.controller.reloading();
        this.runPluginStep(PluginStep.DISABLE, "Example", 400);
        // While the reload still runs, another thread stops the server, for example after SIGTERM
        final Thread shutdown = new Thread(() -> {
            this.controller.stopping();
            this.runPluginStep(PluginStep.DISABLE, "Other", 1200);
        }, "Server Shutdown Thread");
        shutdown.start();
        shutdown.join();
        this.runPluginStep(PluginStep.ENABLE, "Example", 300);
        reload.close();
        assertEquals(LifecycleController.State.STOPPING, this.controller.state(), "the end of the reload does not end the shutdown");

        this.controller.stopped();

        assertEquals(List.of("INFO Stopped the server in 1.5 s. Plugins took 1.2 s to stop. Plugin: Other 1.2 s."), this.log);
    }

    @Test
    void aPluginStepThatBeganBeforeTheShutdownIsNotPartOfItsSummary() throws InterruptedException {
        this.controller.startupBegins();
        this.controller.startupFinished();
        // A plugin manager enables a plugin at runtime, and another thread stops the server meanwhile
        final LifecyclePhase runtimeEnable = this.controller.pluginStep(PluginStep.ENABLE, "Runtime", "Runtime v1.0");
        this.clock.advanceMillis(5000);
        final Thread shutdown = new Thread(this.controller::stopping, "Server Shutdown Thread");
        shutdown.start();
        shutdown.join();
        this.clock.advanceMillis(1000);
        runtimeEnable.close();
        this.runPluginStep(PluginStep.DISABLE, "Example", 300);

        this.controller.stopped();

        assertEquals(List.of("INFO Stopped the server in 1.3 s. Plugins took 300 ms to stop. Plugin: Example 300 ms."), this.log);
    }

    @Test
    void summariesCanBeDisabled() {
        final LifecycleController quiet = this.controller(new LifecycleController.Settings(false, true, 1L, 1L));
        quiet.startupBegins();
        try (LifecyclePhase _ = quiet.pluginStep(PluginStep.ENABLE, "Example", "Example v1.0")) {
            this.clock.advanceMillis(10);
        }
        quiet.startupFinished();
        quiet.stopping();
        quiet.stopped();

        assertEquals(List.of(), this.log);
    }

    @Test
    void theWatchdogReportsOnlyWhileTheServerIsNotRunningNormally() {
        this.controller.startupBegins();
        assertEquals(List.of(this.watchdog()), this.startedWatchdogs, "started with the first lifecycle phase");

        // Startup
        try (LifecyclePhase _ = this.controller.pluginStep(PluginStep.ENABLE, "Slow", "Slow v1.0")) {
            this.clock.advanceSeconds(30);
            this.watchdog().check();
        }
        assertEquals(2, this.log.size(), "report and 'finished' message: " + this.log);
        assertTrue(this.log.get(0).startsWith("WARN Lifecycle watchdog: Enabling plugin Slow v1.0 has not finished after 30 s."), this.log.get(0));
        assertEquals("INFO Lifecycle watchdog: Enabling plugin Slow v1.0 finished after 30.0 s.", this.log.get(1));
        this.controller.startupFinished();
        this.log.clear();

        // Running normally: the upstream watchdog is responsible
        try (LifecyclePhase _ = this.controller.pluginStep(PluginStep.ENABLE, "Runtime", "Runtime v1.0")) {
            this.clock.advanceSeconds(300);
            this.watchdog().check();
        }
        assertEquals(List.of(), this.log);

        // Reload
        try (LifecyclePhase _ = this.controller.reloading()) {
            assertEquals(LifecycleController.State.RELOADING, this.controller.state());
            this.clock.advanceSeconds(30);
            this.watchdog().check();
        }
        assertEquals(LifecycleController.State.RUNNING, this.controller.state());
        assertTrue(this.log.getFirst().startsWith("WARN Lifecycle watchdog: Reloading the server has not finished after 30 s."), this.log.getFirst());
        this.log.clear();

        // Shutdown
        this.controller.stopping();
        this.clock.advanceSeconds(30);
        this.watchdog().check();
        assertTrue(this.log.getFirst().startsWith("WARN Lifecycle watchdog: Stopping the server has not finished after 30 s."), this.log.getFirst());
    }

    @Test
    void theWatchdogIsNotStartedWhenDisabled() {
        final LifecycleController noWatchdog = this.controller(new LifecycleController.Settings(true, false, 1L, 1L));
        noWatchdog.startupBegins();
        noWatchdog.stopping();

        assertEquals(List.of(), this.startedWatchdogs);
        assertNull(noWatchdog.watchdog());
    }

    @Test
    void aFailedStartupEndsWhenTheServerStops() {
        this.controller.startupBegins();
        // An exception in initServer skips startupFinished, and runServer stops the server on the same thread
        this.controller.stopping();

        final List<LifecycleMonitor.OpenPhase> open = this.controller.monitor().openPhases();
        assertEquals(1, open.size());
        assertEquals("Stopping the server", open.getFirst().phase().description());
        assertNull(open.getFirst().phase().parent(), "the startup phase was closed");

        this.controller.startupFinished();
        assertEquals(LifecycleController.State.STOPPING, this.controller.state(), "a late startupFinished does not change the state");
    }

    @Test
    void aStartupThatIsStillRunningOnAnotherThreadStaysWatched() throws InterruptedException {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final Thread thread = new Thread(() -> {
            this.controller.startupBegins();
            started.countDown();
            try {
                release.await();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "Stuck server thread");
        thread.start();
        try {
            started.await();
            // The shutdown thread stops a server whose startup is stuck
            this.controller.stopping();

            final Set<String> open = this.controller.monitor().openPhases().stream()
                .map(phase -> phase.thread().getName() + ": " + phase.phase().description())
                .collect(Collectors.toSet());
            assertEquals(Set.of(Thread.currentThread().getName() + ": Stopping the server", "Stuck server thread: Starting the server"), open);
        } finally {
            release.countDown();
            thread.join();
        }
    }

    @Test
    void stoppingTwiceIsIgnored() {
        this.controller.startupBegins();
        this.controller.startupFinished();
        this.controller.stopping();
        this.controller.stopping();

        assertEquals(1, this.controller.monitor().openPhases().size());
    }
}
