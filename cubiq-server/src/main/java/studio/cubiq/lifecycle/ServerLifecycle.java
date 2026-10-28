package studio.cubiq.lifecycle;

import io.papermc.paper.plugin.configuration.PluginMeta;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import studio.cubiq.Cubiq;

/**
 * Entry point for the server code that reports the steps of its lifecycle to the lifecycle diagnostics.
 *
 * <p>Every method is safe to call from upstream code: if the diagnostics fail, the failure is logged once and the
 * method returns normally, with an untracked phase where a phase is expected.</p>
 *
 * <p>The diagnostics are configured with system properties:</p>
 * <ul>
 *     <li>{@value LifecycleController#WATCHDOG_PROPERTY}: {@code false} disables the watchdog. Paper's
 *     {@value LifecycleController#DISABLE_WATCHDOG_PROPERTY} property disables it as well.</li>
 *     <li>{@value LifecycleController#WARN_AFTER_PROPERTY}: seconds without progress before a step is reported
 *     (default 30).</li>
 *     <li>{@value LifecycleController#WARN_EVERY_PROPERTY}: seconds between reports while a step stays stuck
 *     (default 60).</li>
 *     <li>{@value LifecycleController#SUMMARY_PROPERTY}: {@code false} disables the summaries of how long starting
 *     and stopping the server and the plugins took.</li>
 *     <li>{@value LifecycleController#REPORT_PROPERTY}: a file to write how long starting and stopping the server
 *     took to, as JSON (see {@link LifecycleReport}).</li>
 * </ul>
 */
public final class ServerLifecycle {

    private static final Logger LOGGER = Cubiq.LOGGER;
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static final LifecycleController CONTROLLER = new LifecycleController(
        LifecycleController.Settings.fromSystemProperties(),
        System::nanoTime,
        ThreadDumps::dump,
        new LifecycleController.Log() {
            @Override
            public void info(final String message) {
                LOGGER.info(message);
            }

            @Override
            public void warn(final String message) {
                LOGGER.warn(message);
            }

            @Override
            public void warn(final String message, final Throwable cause) {
                LOGGER.warn(message, cause);
            }
        }
    );

    private ServerLifecycle() {
    }

    /**
     * Opens the phase in which the plugins are discovered and Minecraft and the plugins are bootstrapped, before the
     * server is created.
     *
     * @return the open phase, to be closed when bootstrapping has finished
     */
    public static LifecyclePhase bootstrapping() {
        try {
            return CONTROLLER.bootstrapping();
        } catch (final RuntimeException | LinkageError ex) {
            return failed(ex);
        }
    }

    /**
     * Opens the phase of a step the server takes while it starts or stops, for example loading the plugins. Each step
     * is progress for the watchdog, its description tells the server owner which step does not finish, and the
     * breakdowns of startup and shutdown report how long it took.
     *
     * @param step the step
     * @return the open phase, to be closed when the step has finished
     */
    public static LifecyclePhase step(final ServerStep step) {
        return step(step, null);
    }

    /**
     * Opens the phase of a step the server takes for one subject while it starts or stops, for example loading a
     * world.
     *
     * @param step    the step
     * @param subject what the step works on, for example the name of the world, or {@code null}
     * @return the open phase, to be closed when the step has finished
     */
    public static LifecyclePhase step(final ServerStep step, final @Nullable String subject) {
        try {
            return CONTROLLER.serverStep(step, subject);
        } catch (final RuntimeException | LinkageError ex) {
            return failed(ex);
        }
    }

    /**
     * Opens the phase of a step of a plugin.
     *
     * @param step the step
     * @param meta the plugin's metadata
     * @return the open phase, to be closed when the step has finished
     */
    public static LifecyclePhase pluginStep(final PluginStep step, final PluginMeta meta) {
        try {
            return CONTROLLER.pluginStep(step, meta.getName(), meta.getDisplayName());
        } catch (final RuntimeException | LinkageError ex) {
            return failed(ex);
        }
    }

    /**
     * Opens the phase of a step of a plugin.
     *
     * @param step   the step
     * @param plugin the plugin
     * @return the open phase, to be closed when the step has finished
     */
    public static LifecyclePhase pluginStep(final PluginStep step, final Plugin plugin) {
        try {
            return pluginStep(step, plugin.getPluginMeta());
        } catch (final RuntimeException | LinkageError ex) {
            return failed(ex);
        }
    }

    /**
     * Called by the server thread when it starts to start the server.
     */
    public static void startupBegins() {
        try {
            CONTROLLER.startupBegins();
        } catch (final RuntimeException | LinkageError ex) {
            failed(ex);
        }
    }

    /**
     * Called when the server has started ({@code Done}).
     *
     * @param startupMillis how long the startup took, as the server reports it
     */
    public static void startupFinished(final long startupMillis) {
        try {
            CONTROLLER.startupFinished(TimeUnit.MILLISECONDS.toNanos(startupMillis));
        } catch (final RuntimeException | LinkageError ex) {
            failed(ex);
        }
    }

    /**
     * Opens the phase of a reload.
     *
     * @return the open phase, to be closed when the reload has finished
     */
    public static LifecyclePhase reloading() {
        try {
            return CONTROLLER.reloading();
        } catch (final RuntimeException | LinkageError ex) {
            return failed(ex);
        }
    }

    /**
     * Called when the server starts to stop, once the upstream watchdog has been stopped.
     */
    public static void stopping() {
        try {
            CONTROLLER.stopping();
        } catch (final RuntimeException | LinkageError ex) {
            failed(ex);
        }
    }

    /**
     * Called when the server has stopped, before logging is shut down.
     */
    public static void stopped() {
        try {
            CONTROLLER.stopped();
        } catch (final RuntimeException | LinkageError ex) {
            failed(ex);
        }
    }

    private static LifecyclePhase failed(final Throwable ex) {
        // A bug in this diagnostic must never affect the server
        if (FAILED.compareAndSet(false, true)) {
            LOGGER.warn("The lifecycle diagnostics failed; further errors are not logged", ex);
        }
        return LifecyclePhase.UNTRACKED;
    }
}
