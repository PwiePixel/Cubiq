package studio.cubiq.lifecycle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleControllerTest {

    private static final LifecycleController.Settings SETTINGS = new LifecycleController.Settings(
        true, true, TimeUnit.SECONDS.toNanos(30), TimeUnit.SECONDS.toNanos(60), null
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

    private void runServerStep(final ServerStep step, final @Nullable String subject, final long millis) {
        try (LifecyclePhase _ = this.controller.serverStep(step, subject)) {
            this.clock.advanceMillis(millis);
        }
    }

    /** Starts the server without steps and forgets the startup's summary. */
    private void startUp() {
        this.controller.startupBegins();
        this.controller.startupFinished(TimeUnit.SECONDS.toNanos(1));
        this.log.clear();
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

        this.controller.startupFinished(TimeUnit.SECONDS.toNanos(2));

        assertEquals(LifecycleController.State.RUNNING, this.controller.state());
        assertEquals(
            List.of(
                "INFO Startup took 2.0 s: bootstrapping 100 ms, other 1.9 s.",
                "INFO Plugins took 1.9 s to start (bootstrap 100 ms, load 200 ms, enable 1.6 s). Slowest plugins: Example 1.8 s, Other 70 ms."
            ),
            this.log
        );
        assertEquals(List.of(), this.controller.monitor().openPhases(), "startup is over");
    }

    @Test
    void logsNoPluginSummaryWithoutPlugins() {
        this.controller.startupBegins();
        this.controller.startupFinished(TimeUnit.MILLISECONDS.toNanos(1500));

        assertEquals(List.of("INFO Startup took 1.5 s."), this.log);
    }

    @Test
    void logsWhichStepsOfTheStartupTookHowLong() {
        try (LifecyclePhase _ = this.controller.bootstrapping()) {
            this.runPluginStep(PluginStep.BOOTSTRAP, "Example", 100);
            this.clock.advanceMillis(900);
        }
        this.runServerStep(ServerStep.LOADING_DATA_PACKS, null, 2000);
        this.controller.startupBegins();
        this.runServerStep(ServerStep.SETTING_UP_THE_SERVER, null, 350);
        try (LifecyclePhase _ = this.controller.serverStep(ServerStep.LOADING_PLUGINS, null)) {
            this.runPluginStep(PluginStep.LOAD, "Example", 200);
        }
        try (LifecyclePhase _ = this.controller.serverStep(ServerStep.ENABLING_PLUGINS, null)) {
            this.runPluginStep(PluginStep.ENABLE, "Example", 400);
        }
        try (LifecyclePhase _ = this.controller.serverStep(ServerStep.LOADING_WORLDS, null)) {
            this.runServerStep(ServerStep.LOADING_WORLD, "world", 1000);
            this.runServerStep(ServerStep.LOADING_WORLD, "world_nether", 500);
            this.runServerStep(ServerStep.PREPARING_WORLD, "world", 2000);
            this.runServerStep(ServerStep.PREPARING_WORLD, "world_nether", 300);
            this.clock.advanceMillis(100);
        }
        try (LifecyclePhase _ = this.controller.serverStep(ServerStep.ENABLING_PLUGINS, null)) {
            this.runPluginStep(PluginStep.ENABLE, "Other", 600);
        }
        this.runServerStep(ServerStep.RUNNING_DELAYED_INIT_TASKS, null, 50);

        this.controller.startupFinished(TimeUnit.MILLISECONDS.toNanos(9500));

        assertEquals(
            "INFO Startup took 9.5 s: bootstrapping 1.0 s, data packs 2.0 s, server setup 350 ms, loading plugins 200 ms, enabling plugins 1.0 s, "
                + "worlds 3.9 s (world 3.0 s, world_nether 800 ms), delayed init tasks 50 ms, other 1.0 s.",
            this.log.getFirst()
        );
    }

    @Test
    void logsWhichStepsOfTheShutdownTookHowLong() {
        this.startUp();
        // An autosave while the server runs is not part of the shutdown
        this.runServerStep(ServerStep.SAVING_WORLD, "world", 500);

        this.controller.stopping();
        try (LifecyclePhase _ = this.controller.serverStep(ServerStep.DISABLING_PLUGINS, null)) {
            this.runPluginStep(PluginStep.DISABLE, "Example", 1200);
        }
        this.runServerStep(ServerStep.WAITING_FOR_PLUGIN_TASKS, null, 0);
        this.runServerStep(ServerStep.SAVING_PLAYERS, null, 100);
        try (LifecyclePhase _ = this.controller.serverStep(ServerStep.SAVING_WORLDS, null)) {
            this.runServerStep(ServerStep.SAVING_WORLD, "world", 700);
            this.runServerStep(ServerStep.SAVING_WORLD, "world_nether", 200);
        }
        // Paper's background tasks before the region files, Minecraft's after them; the summary adds them up
        this.runServerStep(ServerStep.WAITING_FOR_BACKGROUND_TASKS, null, 20);
        this.runServerStep(ServerStep.WAITING_FOR_REGION_FILES, null, 50);
        this.runServerStep(ServerStep.WAITING_FOR_BACKGROUND_TASKS, null, 30);
        this.clock.advanceMillis(100);
        this.controller.stopped();

        assertEquals(
            List.of("INFO Stopped the server in 2.4 s: disabling plugins 1.2 s, players 100 ms, worlds 900 ms (world 700 ms, world_nether 200 ms), "
                + "background tasks 50 ms, region files 50 ms, other 100 ms. Plugins took 1.2 s to stop. Plugin: Example 1.2 s."),
            this.log
        );
    }

    @Test
    void aStepThatIsStillOpenWhenTheServerHasStoppedIsPartOfTheShutdown() {
        this.startUp();
        this.controller.stopping();
        // For example a shutdown that fails while saving, so that the step is closed with the shutdown
        this.controller.serverStep(ServerStep.SAVING_WORLDS, null);
        this.clock.advanceMillis(300);
        this.controller.stopped();

        assertEquals(List.of("INFO Stopped the server in 300 ms: worlds 300 ms."), this.log);
    }

    @Test
    void aStepThatAnotherThreadRunsDuringTheShutdownIsNotPartOfIt() throws InterruptedException {
        this.startUp();
        // The shutdown thread stops a server that does not respond, while the server thread still saves a world
        this.controller.stopping();
        final Thread serverThread = new Thread(() -> this.runServerStep(ServerStep.SAVING_WORLD, "world_nether", 1000), "Server thread");
        serverThread.start();
        serverThread.join();
        try (LifecyclePhase _ = this.controller.serverStep(ServerStep.SAVING_WORLDS, null)) {
            this.runServerStep(ServerStep.SAVING_WORLD, "world", 500);
        }

        this.controller.stopped();

        assertEquals(List.of("INFO Stopped the server in 1.5 s: worlds 500 ms (world 500 ms), other 1.0 s."), this.log);
    }

    @Test
    void writesTheReportWhenTheServerHasStartedAndWhenItHasStopped(@TempDir final Path directory) throws IOException {
        final Path report = directory.resolve("reports").resolve("lifecycle.json");
        final LifecycleController reporting = this.controller(new LifecycleController.Settings(false, true, 1L, 1L, report.toString()));
        try (LifecyclePhase _ = reporting.bootstrapping()) {
            this.clock.advanceMillis(1000);
        }
        reporting.startupBegins();
        try (LifecyclePhase _ = reporting.pluginStep(PluginStep.ENABLE, "Example", "Example v1.0")) {
            this.clock.advanceMillis(250);
        }
        reporting.startupFinished(TimeUnit.MILLISECONDS.toNanos(1500));

        final JsonObject started = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
        assertEquals(1, started.get("formatVersion").getAsInt());
        assertEquals(1500.0, started.getAsJsonObject("startup").get("totalMillis").getAsDouble());
        assertEquals(
            "[{\"name\":\"bootstrapping\",\"millis\":1000.0},{\"name\":\"other\",\"millis\":500.0}]",
            started.getAsJsonObject("startup").get("steps").toString()
        );
        assertEquals("[{\"name\":\"Example\",\"enableMillis\":250.0}]", started.getAsJsonObject("startup").get("plugins").toString());
        assertFalse(started.has("shutdown"));

        reporting.stopping();
        try (LifecyclePhase _ = reporting.serverStep(ServerStep.SAVING_PLAYERS, null)) {
            // The report contains steps that took no time
        }
        try (LifecyclePhase _ = reporting.serverStep(ServerStep.SAVING_WORLDS, null)) {
            this.clock.advanceMillis(700);
        }
        reporting.stopped();

        final JsonObject stopped = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
        assertEquals(started.get("startup"), stopped.get("startup"));
        assertEquals(700.0, stopped.getAsJsonObject("shutdown").get("totalMillis").getAsDouble());
        assertEquals(
            "[{\"name\":\"players\",\"millis\":0.0},{\"name\":\"worlds\",\"millis\":700.0},{\"name\":\"other\",\"millis\":0.0}]",
            stopped.getAsJsonObject("shutdown").get("steps").toString()
        );
        try (Stream<Path> files = Files.list(report.getParent())) {
            assertEquals(List.of(report.getFileName()), files.map(Path::getFileName).toList(), "no temporary file is left");
        }
        assertEquals(List.of(), this.log);
    }

    @Test
    void aReportThatCannotBeWrittenIsAWarning(@TempDir final Path directory) throws IOException {
        // A directory that is not empty cannot be replaced by the report
        final Path report = Files.createDirectories(directory.resolve("lifecycle.json"));
        Files.writeString(report.resolve("file"), "");
        final LifecycleController reporting = this.controller(new LifecycleController.Settings(false, true, 1L, 1L, report.toString()));
        reporting.startupBegins();

        reporting.startupFinished(TimeUnit.SECONDS.toNanos(1));

        assertEquals(LifecycleController.State.RUNNING, reporting.state());
        assertEquals(1, this.log.size(), this.log.toString());
        assertTrue(this.log.getFirst().startsWith("WARN Could not write the lifecycle report to " + report + ": "), this.log.getFirst());
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(List.of(report.getFileName()), files.map(Path::getFileName).toList(), "no temporary file is left");
        }
    }

    @Test
    void theReportPathIsOnlyResolvedWhenTheReportIsWritten() {
        final String previous = System.getProperty(LifecycleController.REPORT_PROPERTY);
        try {
            System.setProperty(LifecycleController.REPORT_PROPERTY, "  ");
            assertNull(LifecycleController.Settings.fromSystemProperties().report());
            System.setProperty(LifecycleController.REPORT_PROPERTY, " reports/lifecycle.json ");
            assertEquals("reports/lifecycle.json", LifecycleController.Settings.fromSystemProperties().report());
            // Reading the settings must not fail on a path that the file system rejects, or the server would not start
            System.setProperty(LifecycleController.REPORT_PROPERTY, "lifecycle\0.json");
            assertEquals("lifecycle\0.json", LifecycleController.Settings.fromSystemProperties().report());
        } finally {
            if (previous == null) {
                System.clearProperty(LifecycleController.REPORT_PROPERTY);
            } else {
                System.setProperty(LifecycleController.REPORT_PROPERTY, previous);
            }
        }
    }

    @Test
    void aReportPathThatTheFileSystemRejectsIsAWarning() {
        // Every file system rejects the NUL character, Windows also characters such as ':' outside the drive
        final LifecycleController reporting = this.controller(new LifecycleController.Settings(false, true, 1L, 1L, "lifecycle\0.json"));
        reporting.startupBegins();

        reporting.startupFinished(TimeUnit.SECONDS.toNanos(1));

        assertEquals(LifecycleController.State.RUNNING, reporting.state());
        assertEquals(1, this.log.size(), this.log.toString());
        assertTrue(this.log.getFirst().startsWith("WARN Could not write the lifecycle report to lifecycle\0.json: java.nio.file.InvalidPathException"), this.log.getFirst());
    }

    @Test
    void bootstrappingReplacesTheReportOfAnEarlierRun(@TempDir final Path directory) throws IOException {
        final Path report = directory.resolve("lifecycle.json");
        Files.writeString(report, LifecycleReport.toJson(new TimingReport(1L, List.of(), List.of()), new TimingReport(1L, List.of(), List.of())));
        final LifecycleController reporting = this.controller(new LifecycleController.Settings(false, true, 1L, 1L, report.toString()));

        // A run that fails while bootstrapping neither finishes its startup nor stops the server
        try (LifecyclePhase _ = reporting.bootstrapping()) {
            this.clock.advanceMillis(1000);
        }

        assertEquals("{\n  \"formatVersion\": 1\n}\n", Files.readString(report));
        assertEquals(List.of(), this.log);
    }

    @Test
    void logsHowLongTheServerTookToStop() {
        this.controller.startupBegins();
        this.controller.startupFinished(TimeUnit.SECONDS.toNanos(1));
        this.log.clear();
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

        this.controller.startupFinished(TimeUnit.MILLISECONDS.toNanos(1800));

        assertEquals(
            List.of("INFO Startup took 1.8 s.", "INFO Plugins took 1.8 s to start (enable 1.7 s, disable 100 ms). Plugin: Example 1.8 s."),
            this.log
        );
    }

    @Test
    void aShutdownDuringAReloadSummarizesOnlyThePluginsTheShutdownDisabled() throws InterruptedException {
        this.startUp();
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
        this.startUp();
        // A plugin manager enables a plugin at runtime, and another thread stops the server meanwhile
        final LifecyclePhase runtimeEnable = this.controller.pluginStep(PluginStep.ENABLE, "Runtime", "Runtime v1.0");
        this.clock.advanceMillis(5000);
        final Thread shutdown = new Thread(() -> {
            this.controller.stopping();
            this.runPluginStep(PluginStep.DISABLE, "Example", 300);
        }, "Server Shutdown Thread");
        shutdown.start();
        shutdown.join();
        this.clock.advanceMillis(1000);
        runtimeEnable.close();

        this.controller.stopped();

        assertEquals(List.of("INFO Stopped the server in 1.3 s. Plugins took 300 ms to stop. Plugin: Example 300 ms."), this.log);
    }

    @Test
    void aPluginThatAnotherThreadDisablesDuringTheShutdownIsNotPartOfItsSummary() throws InterruptedException {
        this.startUp();
        final LifecyclePhase reload = this.controller.reloading();
        // Another thread stops the server while the reload runs, and the reload disables a plugin meanwhile
        final Thread shutdown = new Thread(() -> {
            this.controller.stopping();
            this.runPluginStep(PluginStep.DISABLE, "Other", 1200);
        }, "Server Shutdown Thread");
        shutdown.start();
        shutdown.join();
        this.runPluginStep(PluginStep.DISABLE, "Example", 400);
        reload.close();

        this.controller.stopped();

        assertEquals(List.of("INFO Stopped the server in 1.6 s. Plugins took 1.2 s to stop. Plugin: Other 1.2 s."), this.log);
    }

    @Test
    void summariesCanBeDisabled() {
        final LifecycleController quiet = this.controller(new LifecycleController.Settings(false, true, 1L, 1L, null));
        quiet.startupBegins();
        try (LifecyclePhase _ = quiet.pluginStep(PluginStep.ENABLE, "Example", "Example v1.0")) {
            this.clock.advanceMillis(10);
        }
        quiet.startupFinished(TimeUnit.SECONDS.toNanos(1));
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
        this.controller.startupFinished(TimeUnit.SECONDS.toNanos(30));
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
        final LifecycleController noWatchdog = this.controller(new LifecycleController.Settings(true, false, 1L, 1L, null));
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

        this.controller.startupFinished(TimeUnit.SECONDS.toNanos(1));
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
        this.startUp();
        this.controller.stopping();
        this.controller.stopping();

        assertEquals(1, this.controller.monitor().openPhases().size());
    }
}
