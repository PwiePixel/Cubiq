/**
 * Lifecycle diagnostics: how long each plugin takes to bootstrap, load, enable and disable, and a watchdog that
 * reports startup, reload and shutdown steps that do not finish.
 *
 * <p>Server code reports the steps it runs through {@link studio.cubiq.lifecycle.ServerLifecycle}.
 * {@link studio.cubiq.lifecycle.LifecycleMonitor} keeps track of them per thread, and
 * {@link studio.cubiq.lifecycle.LifecycleWatchdog} reports steps that make no progress while the upstream
 * watchdog, which only watches ticks of a running server, is not active.</p>
 */
@NullMarked
package studio.cubiq.lifecycle;

import org.jspecify.annotations.NullMarked;
