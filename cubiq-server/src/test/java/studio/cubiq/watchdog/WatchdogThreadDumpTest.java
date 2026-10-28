package studio.cubiq.watchdog;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class WatchdogThreadDumpTest {

    private static void awaitState(final Thread thread, final Thread.State state) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != state) {
            if (System.nanoTime() > deadline) {
                fail(thread.getName() + " did not reach " + state + ", it is " + thread.getState());
            }
            Thread.sleep(5);
        }
    }

    private static void holdTheLockInAUniquelyNamedMethod(final Object lock, final CountDownLatch locked, final CountDownLatch release) {
        synchronized (lock) {
            locked.countDown();
            try {
                release.await();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean containsLineStartingWith(final List<String> lines, final String prefix) {
        return lines.stream().anyMatch(line -> line.startsWith(prefix));
    }

    @Test
    void theServerThreadIsDumpedTogetherWithTheThreadThatHoldsItsLock() throws InterruptedException {
        final Object lock = new Object();
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = new Thread(() -> holdTheLockInAUniquelyNamedMethod(lock, locked, release), "Lock holder");
        final Thread server = new Thread(() -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        }, "Server thread under test");
        holder.start();
        try {
            locked.await();
            server.start();
            awaitState(server, Thread.State.BLOCKED);

            final List<String> lines = WatchdogThreadDump.serverThread(server);

            assertEquals(WatchdogThreadDump.SEPARATOR, lines.getFirst());
            assertEquals("Current Thread: Server thread under test", lines.get(1));
            assertEquals("\tThread ID: " + server.threadId() + " | Suspended: false | Native: false | State: BLOCKED", lines.get(2));
            assertTrue(containsLineStartingWith(lines, "\tBlocked on: java.lang.Object@"), String.join("\n", lines));
            assertTrue(lines.stream().anyMatch(line -> line.endsWith(" held by \"Lock holder\" (thread ID " + holder.threadId() + ")")), String.join("\n", lines));
            assertTrue(lines.contains("The server thread waits for a lock held by \"Lock holder\", which is likely the cause:"), String.join("\n", lines));
            assertTrue(lines.contains("Current Thread: Lock holder"), String.join("\n", lines));
            assertTrue(containsLineStartingWith(lines, "\tLocked monitors:"), String.join("\n", lines));
            assertTrue(lines.stream().anyMatch(line -> line.contains("holdTheLockInAUniquelyNamedMethod")), String.join("\n", lines));
        } finally {
            release.countDown();
            holder.join();
            server.join();
        }
    }

    @Test
    void aThreadWithoutLocksIsDumpedLikeUpstream() throws InterruptedException {
        final CountDownLatch release = new CountDownLatch(1);
        final Thread thread = new Thread(() -> {
            try {
                release.await();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "Waiting thread");
        thread.start();
        try {
            awaitState(thread, Thread.State.WAITING);
            final ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId(), Integer.MAX_VALUE);

            final List<String> lines = WatchdogThreadDump.lines(info);

            assertEquals(WatchdogThreadDump.SEPARATOR, lines.get(0));
            assertEquals("Current Thread: Waiting thread", lines.get(1));
            assertTrue(lines.get(3).startsWith("\tWaiting on: java.util.concurrent.CountDownLatch$Sync@"), lines.get(3));
            assertEquals("\tStack:", lines.get(4));
            assertTrue(lines.get(5).startsWith("\t\t"), lines.get(5));
        } finally {
            release.countDown();
            thread.join();
        }
    }

    @Test
    void aThreadThatHasEndedIsReportedAsSuch() throws InterruptedException {
        final Thread ended = new Thread(() -> {
        }, "Ended thread");
        ended.start();
        ended.join();

        assertEquals(
            List.of(WatchdogThreadDump.SEPARATOR, "Current Thread: Ended thread (no longer running)"),
            WatchdogThreadDump.serverThread(ended)
        );
    }
}
