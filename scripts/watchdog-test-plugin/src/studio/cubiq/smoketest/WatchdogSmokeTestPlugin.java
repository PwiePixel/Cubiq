package studio.cubiq.smoketest;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Blocks the server thread on a lock that another thread holds, so that the watchdog reports it, and measures the
 * CPU time the watchdog thread uses while the server does not respond.
 */
public final class WatchdogSmokeTestPlugin extends JavaPlugin {

    private static final Object LOCK = new Object();
    private static final String HOLDER_NAME = "WatchdogSmokeTest lock holder";

    @Override
    public void onEnable() {
        final long stallMillis = Long.getLong("cubiqSmokeTest.stallMillis", 0L);
        if (stallMillis > 0L) {
            // One second after the server has started
            this.getServer().getScheduler().runTaskLater(this, () -> this.blockServerThread(stallMillis), 20L);
        }
    }

    private void blockServerThread(final long stallMillis) {
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        final Thread watchdog = findThread("Watchdog Thread");
        final long watchdogCpuBefore = watchdog == null ? -1L : threads.getThreadCpuTime(watchdog.threadId());
        final CountDownLatch locked = new CountDownLatch(1);
        final Thread holder = new Thread(() -> holdLockInWatchdogSmokeTest(locked, stallMillis), HOLDER_NAME);
        holder.start();
        try {
            locked.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }
        this.getLogger().info("Blocking the server thread for " + stallMillis + " ms on a lock held by \"" + HOLDER_NAME + "\"");
        synchronized (LOCK) {
            // Reached once the holder releases the lock
            LOCK.notifyAll();
        }
        final long watchdogCpuAfter = watchdog == null ? -1L : threads.getThreadCpuTime(watchdog.threadId());
        if (watchdogCpuBefore < 0L || watchdogCpuAfter < 0L) {
            this.getLogger().warning("The CPU time of the watchdog thread could not be measured");
        } else {
            this.getLogger().info("The server thread is free again. Meanwhile the watchdog thread used "
                + (watchdogCpuAfter - watchdogCpuBefore) / 1_000_000L + " ms of CPU time");
        }
    }

    private static void holdLockInWatchdogSmokeTest(final CountDownLatch locked, final long millis) {
        synchronized (LOCK) {
            locked.countDown();
            try {
                Thread.sleep(millis);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Thread findThread(final String name) {
        for (final Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().equals(name)) {
                return thread;
            }
        }
        return null;
    }
}
