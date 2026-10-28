package studio.cubiq.smoketest;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Makes the server thread stop responding twice, so that the watchdog reports it: first it blocks the server thread
 * on a lock that another thread holds, and measures the CPU time the watchdog thread uses meanwhile, then it keeps the
 * server thread busy.
 */
public final class WatchdogSmokeTestPlugin extends JavaPlugin {

    private static final Object LOCK = new Object();
    private static final String HOLDER_NAME = "WatchdogSmokeTest lock holder";
    private static volatile long spins;

    @Override
    public void onEnable() {
        final long stallMillis = Long.getLong("cubiqSmokeTest.stallMillis", 0L);
        final long busyMillis = Long.getLong("cubiqSmokeTest.busyMillis", 0L);
        if (stallMillis > 0L) {
            // One second after the server has started, and the busy stall one second after the server responds again
            this.getServer().getScheduler().runTaskLater(this, () -> {
                this.blockServerThread(stallMillis);
                this.getServer().getScheduler().runTaskLater(this, () -> {
                    this.keepServerThreadBusy(busyMillis);
                    this.getLogger().info("Watchdog smoke test finished");
                }, 20L);
            }, 20L);
        }
    }

    private void keepServerThreadBusy(final long busyMillis) {
        this.getLogger().info("Keeping the server thread busy for " + busyMillis + " ms");
        spinInWatchdogSmokeTest(busyMillis);
        this.getLogger().info("The server thread is no longer busy");
    }

    private static void spinInWatchdogSmokeTest(final long millis) {
        final long end = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < end) {
            spins++;
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
        this.getLogger().info("The server thread is free again");
        final long watchdogCpuAfter = watchdog == null ? -1L : threads.getThreadCpuTime(watchdog.threadId());
        if (watchdogCpuBefore < 0L || watchdogCpuAfter < 0L) {
            this.getLogger().warning("The CPU time of the watchdog thread could not be measured");
        } else {
            this.getLogger().info("While the server thread was blocked, the watchdog thread used "
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
