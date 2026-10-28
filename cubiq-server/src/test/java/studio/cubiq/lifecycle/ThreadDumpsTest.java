package studio.cubiq.lifecycle;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ThreadDumpsTest {

    private static void awaitState(final Thread thread, final Thread.State state) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != state) {
            if (System.nanoTime() > deadline) {
                fail(thread.getName() + " did not reach " + state + ", it is " + thread.getState());
            }
            Thread.sleep(5);
        }
    }

    private static void waitInAUniquelyNamedMethod(final CountDownLatch release) {
        try {
            release.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void holdTheLockInAUniquelyNamedMethod(final Object lock, final CountDownLatch locked, final CountDownLatch release) {
        synchronized (lock) {
            locked.countDown();
            waitInAUniquelyNamedMethod(release);
        }
    }

    @Test
    void dumpContainsTheStackTraceOfTheThread() throws InterruptedException {
        final CountDownLatch release = new CountDownLatch(1);
        final Thread thread = new Thread(() -> waitInAUniquelyNamedMethod(release), "Dumped thread");
        thread.start();
        try {
            awaitState(thread, Thread.State.WAITING);

            final ThreadDumps.Dump result = ThreadDumps.dump(thread);
            final String dump = result.text();

            assertEquals(Thread.State.WAITING, result.state());
            assertTrue(dump.startsWith("\"Dumped thread\" #" + thread.threadId() + " WAITING\n"), dump);
            assertTrue(dump.contains("studio.cubiq.lifecycle.ThreadDumpsTest.waitInAUniquelyNamedMethod(ThreadDumpsTest.java:"), dump);
            assertTrue(dump.contains("\t- waiting on java.util.concurrent.CountDownLatch$Sync@"), dump);
        } finally {
            release.countDown();
            thread.join();
        }
    }

    @Test
    void dumpOfABlockedThreadContainsTheThreadThatHoldsTheLock() throws InterruptedException {
        final Object lock = new Object();
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread owner = new Thread(() -> holdTheLockInAUniquelyNamedMethod(lock, locked, release), "Lock owner");
        final Thread blocked = new Thread(() -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        }, "Blocked thread");
        owner.start();
        try {
            locked.await();
            blocked.start();
            awaitState(blocked, Thread.State.BLOCKED);

            final String dump = ThreadDumps.dump(blocked).text();

            assertTrue(dump.startsWith("\"Blocked thread\" #" + blocked.threadId() + " BLOCKED on a lock held by \"Lock owner\" #" + owner.threadId()), dump);
            assertTrue(dump.contains("\t- blocked on java.lang.Object@"), dump);
            assertTrue(dump.contains("The lock is held by \"Lock owner\":\n\"Lock owner\" #" + owner.threadId()), dump);
            assertTrue(dump.contains("studio.cubiq.lifecycle.ThreadDumpsTest.holdTheLockInAUniquelyNamedMethod(ThreadDumpsTest.java:"), dump);
            assertTrue(dump.contains("\t- locked java.lang.Object@"), dump);
        } finally {
            release.countDown();
            owner.join();
            blocked.join();
        }
    }

    @Test
    void dumpOfAThreadWaitingForAConcurrentLockContainsTheThreadThatHoldsIt() throws InterruptedException {
        final ReentrantLock lock = new ReentrantLock();
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread owner = new Thread(() -> {
            lock.lock();
            try {
                locked.countDown();
                waitInAUniquelyNamedMethod(release);
            } finally {
                lock.unlock();
            }
        }, "Lock owner");
        final Thread waiting = new Thread(() -> {
            lock.lock();
            lock.unlock();
        }, "Waiting thread");
        owner.start();
        try {
            locked.await();
            waiting.start();
            awaitState(waiting, Thread.State.WAITING);

            final ThreadDumps.Dump result = ThreadDumps.dump(waiting);
            final String dump = result.text();

            assertEquals(Thread.State.WAITING, result.state());
            assertTrue(dump.startsWith("\"Waiting thread\" #" + waiting.threadId() + " WAITING on a lock held by \"Lock owner\" #" + owner.threadId()), dump);
            assertTrue(dump.contains("\t- waiting on java.util.concurrent.locks.ReentrantLock$NonfairSync@"), dump);
            assertTrue(dump.contains("The lock is held by \"Lock owner\":\n\"Lock owner\" #" + owner.threadId()), dump);
        } finally {
            release.countDown();
            owner.join();
            waiting.join();
        }
    }

    @Test
    void theConcurrentLocksThatAThreadHoldsAreNotRequested() throws InterruptedException {
        // To find them, HotSpot scans the whole heap with all threads paused
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        final List<String> requests = new ArrayList<>();
        final ThreadMXBean recording = (ThreadMXBean) Proxy.newProxyInstance(
            ThreadMXBean.class.getClassLoader(),
            new Class<?>[] {ThreadMXBean.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getThreadInfo") && args != null && args.length == 3) {
                    requests.add("lockedMonitors=" + args[1] + ", lockedSynchronizers=" + args[2]);
                }
                return method.invoke(threads, args);
            }
        );
        final CountDownLatch release = new CountDownLatch(1);
        final Thread thread = new Thread(() -> waitInAUniquelyNamedMethod(release), "Dumped thread");
        thread.start();
        try {
            awaitState(thread, Thread.State.WAITING);

            ThreadDumps.dump(recording, thread);

            assertEquals(List.of("lockedMonitors=true, lockedSynchronizers=false"), requests);
        } finally {
            release.countDown();
            thread.join();
        }
    }

    @Test
    void deepStackTracesAreShortened() throws InterruptedException {
        final CountDownLatch release = new CountDownLatch(1);
        final Thread thread = new Thread(() -> recurse(ThreadDumps.MAX_FRAMES + 50, release), "Deep thread");
        thread.start();
        try {
            awaitState(thread, Thread.State.WAITING);

            final String dump = ThreadDumps.dump(thread).text();

            final long frames = dump.lines().filter(line -> line.startsWith("\tat ")).count();
            assertTrue(frames == ThreadDumps.MAX_FRAMES, "frames: " + frames);
            assertTrue(dump.contains("\t... "), dump);
        } finally {
            release.countDown();
            thread.join();
        }
    }

    private static void recurse(final int depth, final CountDownLatch release) {
        if (depth == 0) {
            waitInAUniquelyNamedMethod(release);
        } else {
            recurse(depth - 1, release);
        }
    }
}
