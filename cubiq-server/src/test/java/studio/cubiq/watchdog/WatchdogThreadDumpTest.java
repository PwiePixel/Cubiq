package studio.cubiq.watchdog;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;

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
    void theOwnerOfAConcurrentLockIsFoundWithoutScanningTheHeap() throws InterruptedException {
        final ReentrantLock lock = new ReentrantLock();
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = new Thread(() -> {
            lock.lock();
            try {
                locked.countDown();
                release.await();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "Lock holder");
        final Thread server = new Thread(() -> {
            lock.lock();
            lock.unlock();
        }, "Server thread under test");
        holder.start();
        try {
            locked.await();
            server.start();
            awaitState(server, Thread.State.WAITING);

            final List<String> lines = WatchdogThreadDump.serverThread(server);

            assertTrue(containsLineStartingWith(lines, "\tWaiting on: java.util.concurrent.locks.ReentrantLock$NonfairSync@"), String.join("\n", lines));
            assertTrue(lines.contains("The server thread waits for a lock held by \"Lock holder\", which is likely the cause:"), String.join("\n", lines));
            assertTrue(lines.contains("Current Thread: Lock holder"), String.join("\n", lines));
            // Early warnings leave out the concurrent locks a thread holds, because finding them scans the whole heap
            // with all threads paused
            assertFalse(containsLineStartingWith(lines, "\tLocked synchronizers:"), String.join("\n", lines));
        } finally {
            release.countDown();
            holder.join();
            server.join();
        }
    }

    @Test
    void theConcurrentLocksOfAFullDumpAreListed() throws InterruptedException {
        final ReentrantLock lock = new ReentrantLock();
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = new Thread(() -> {
            lock.lock();
            try {
                locked.countDown();
                release.await();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "Lock holder");
        holder.start();
        try {
            locked.await();
            // Upstream's dump of all threads on a long timeout captures them
            final ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(new long[] {holder.threadId()}, true, true)[0];

            final List<String> lines = WatchdogThreadDump.lines(info);

            assertTrue(lines.contains("\tLocked synchronizers:"), String.join("\n", lines));
            assertTrue(containsLineStartingWith(lines, "\t\tjava.util.concurrent.locks.ReentrantLock$NonfairSync@"), String.join("\n", lines));
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void aConcurrentLockHeldByAVirtualThreadIsExplained() throws InterruptedException {
        final ReentrantLock lock = new ReentrantLock();
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = Thread.ofVirtual().name("Virtual lock holder").start(() -> {
            lock.lock();
            try {
                locked.countDown();
                release.await();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        final Thread server = new Thread(() -> {
            lock.lock();
            lock.unlock();
        }, "Server thread under test");
        try {
            locked.await();
            server.start();
            awaitState(server, Thread.State.WAITING);

            final List<String> lines = WatchdogThreadDump.serverThread(server);

            assertTrue(lines.contains("The server thread waits for a lock held by \"Virtual lock holder\" (thread ID " + holder.threadId()
                + "), whose stack trace cannot be taken here: it is a virtual thread or has ended. " + WatchdogThreadDump.VIRTUAL_THREAD_DUMP
            ), String.join("\n", lines));
        } finally {
            release.countDown();
            holder.join();
            server.join();
        }
    }

    @Test
    void aMonitorHeldByARunningVirtualThreadIsNotBlamedOnTheThreadItRunsOn() throws InterruptedException {
        final Object monitor = new Object();
        final CountDownLatch locked = new CountDownLatch(1);
        final AtomicBoolean release = new AtomicBoolean();
        final Thread holder = Thread.ofVirtual().name("Virtual monitor holder").start(() -> {
            synchronized (monitor) {
                locked.countDown();
                while (!release.get()) {
                    Thread.onSpinWait();
                }
            }
        });
        final Thread server = new Thread(() -> {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }, "Server thread under test");
        try {
            locked.await();
            server.start();
            awaitState(server, Thread.State.BLOCKED);

            final String dump = String.join("\n", WatchdogThreadDump.serverThread(server));

            assertTrue(dump.contains("The server thread waits for a lock held by the virtual thread \"Virtual monitor holder\" (thread ID "
                + holder.threadId() + "), which runs on \""), dump);
            assertFalse(dump.contains("which is likely the cause"), dump);
        } finally {
            release.set(true);
            holder.join();
            server.join();
        }
    }

    @Test
    void aMonitorHeldByAWaitingVirtualThreadIsExplained() throws InterruptedException {
        final Object monitor = new Object();
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = Thread.ofVirtual().name("Virtual monitor holder").start(() -> {
            synchronized (monitor) {
                locked.countDown();
                try {
                    release.await();
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        final Thread server = new Thread(() -> {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }, "Server thread under test");
        try {
            locked.await();
            server.start();
            awaitState(server, Thread.State.BLOCKED);

            final String dump = String.join("\n", WatchdogThreadDump.serverThread(server));

            // The JVM reports neither the monitor nor its owner when a virtual thread that is not running holds it
            assertTrue(dump.contains(", which happens when a virtual thread holds the monitor. " + WatchdogThreadDump.VIRTUAL_THREAD_DUMP), dump);
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
    void formattingNeverThrowsNotEvenWhenMemoryRunsOut() {
        // The watchdog thread must survive a report to halt or restart a server that does not respond
        final ThreadInfo info = Mockito.mock(ThreadInfo.class);
        when(info.getThreadName()).thenThrow(new OutOfMemoryError("Java heap space"));

        // Caught here because JUnit does not let an OutOfMemoryError fail a test; it ends the test run
        try {
            assertEquals(List.of(), WatchdogThreadDump.lines(info));
        } catch (final OutOfMemoryError ex) {
            fail("formatting threw " + ex);
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
