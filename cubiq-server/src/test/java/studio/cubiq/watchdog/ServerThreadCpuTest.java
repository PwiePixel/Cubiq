package studio.cubiq.watchdog;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ServerThreadCpuTest {

    private long cpuNanos;
    private long gcPauseMillis = 5_000L;
    private long nowMillis = 100_000L;
    private final ServerThreadCpu cpu = new ServerThreadCpu(thread -> this.cpuNanos, () -> this.gcPauseMillis, () -> this.nowMillis);
    private final Thread serverThread = Thread.currentThread();

    private void advance(final long millis, final long cpuMillis) {
        this.advance(millis, cpuMillis, 0L);
    }

    private void advance(final long millis, final long cpuMillis, final long gcPauseMillis) {
        this.nowMillis += millis;
        this.cpuNanos += TimeUnit.MILLISECONDS.toNanos(cpuMillis);
        this.gcPauseMillis += gcPauseMillis;
    }

    private static int percent(final String description) {
        final Matcher matcher = Pattern.compile("\\((\\d+)%\\)").matcher(description);
        assertTrue(matcher.find(), description);
        return Integer.parseInt(matcher.group(1));
    }

    @Test
    void aThreadThatUsesTheCpuIsBusy() {
        this.cpu.observe(1L, this.serverThread);
        this.advance(10_000L, 9_900L);

        assertEquals(
            "The server thread used 9900 ms of CPU time in the last 10000 ms (99%), so it is busy, for example in an endless loop or a very long computation.",
            this.cpu.describe(this.serverThread)
        );
    }

    @Test
    void aThreadThatDoesNotUseTheCpuIsWaiting() {
        this.cpu.observe(1L, this.serverThread);
        this.advance(10_000L, 30L);

        final String description = this.cpu.describe(this.serverThread);
        assertNotNull(description);
        assertTrue(description.endsWith(
            "(0%), so it is mostly waiting, for example for a lock, a file, the network or a database, or it gets too little CPU time from the operating system."
        ), description);
    }

    @Test
    void aThreadThatGarbageCollectionStopsIsReportedAsSuch() {
        // A thread gets no CPU time while garbage collection pauses all threads, for example when memory runs out
        this.cpu.observe(1L, this.serverThread);
        this.advance(10_000L, 300L, 8_000L);

        assertEquals(
            "The server thread used 300 ms of CPU time in the last 10000 ms (3%), and garbage collection paused all threads for 8000 ms (80%),"
                + " so it is mostly stopped by garbage collection, which happens for example when the memory of the server is nearly full.",
            this.cpu.describe(this.serverThread)
        );
    }

    @Test
    void longerGarbageCollectionPausesAreMentionedForEveryThread() {
        this.cpu.observe(1L, this.serverThread);
        this.advance(10_000L, 8_500L, 1_200L);

        assertEquals(
            "The server thread used 8500 ms of CPU time in the last 10000 ms (85%), and garbage collection paused all threads for 1200 ms (12%),"
                + " so it is busy, for example in an endless loop or a very long computation.",
            this.cpu.describe(this.serverThread)
        );
    }

    @Test
    void shortGarbageCollectionPausesAreNotMentioned() {
        this.cpu.observe(1L, this.serverThread);
        this.advance(10_000L, 30L, 500L);

        final String description = this.cpu.describe(this.serverThread);
        assertNotNull(description);
        assertFalse(description.contains("garbage collection"), description);
    }

    @Test
    void garbageCollectionThatCannotBeMeasuredIsLeftOut() {
        final ServerThreadCpu withoutGc = new ServerThreadCpu(thread -> this.cpuNanos, () -> -1L, () -> this.nowMillis);
        withoutGc.observe(1L, this.serverThread);
        this.advance(10_000L, 9_900L);

        assertEquals(
            "The server thread used 9900 ms of CPU time in the last 10000 ms (99%), so it is busy, for example in an endless loop or a very long computation.",
            withoutGc.describe(this.serverThread)
        );
    }

    @Test
    void aFailedMeasurementIsNotThrown() {
        // An exception would end the watchdog thread, even an OutOfMemoryError on a server that runs out of memory
        final ServerThreadCpu failing = new ServerThreadCpu(thread -> {
            throw new OutOfMemoryError("Java heap space");
        }, () -> this.gcPauseMillis, () -> this.nowMillis);

        // Caught here because JUnit does not let an OutOfMemoryError fail a test; it ends the test run
        try {
            failing.observe(1L, this.serverThread);
            assertNull(failing.describe(this.serverThread));
        } catch (final OutOfMemoryError ex) {
            fail("the measurement threw " + ex);
        }
    }

    @Test
    void inBetweenIsPartlyBusy() {
        this.cpu.observe(1L, this.serverThread);
        this.advance(10_000L, 5_000L);

        final String description = this.cpu.describe(this.serverThread);
        assertNotNull(description);
        assertTrue(description.endsWith("(50%), so it is partly busy and partly waiting."), description);
    }

    @Test
    void theBaselineMovesWithEveryTick() {
        this.cpu.observe(1L, this.serverThread);
        this.advance(1_000L, 1_000L);
        // The server ticked: what it did before does not count anymore
        this.cpu.observe(2L, this.serverThread);
        this.advance(10_000L, 0L);

        final String description = this.cpu.describe(this.serverThread);
        assertNotNull(description);
        assertTrue(description.startsWith("The server thread used 0 ms of CPU time in the last 10000 ms (0%)"), description);
    }

    @Test
    void checksWithoutATickKeepTheBaseline() {
        this.cpu.observe(1L, this.serverThread);
        this.advance(5_000L, 5_000L);
        this.cpu.observe(1L, this.serverThread);
        this.advance(5_000L, 5_000L);

        final String description = this.cpu.describe(this.serverThread);
        assertNotNull(description);
        assertTrue(description.startsWith("The server thread used 10000 ms of CPU time in the last 10000 ms (100%)"), description);
    }

    @Test
    void nothingIsReportedWhenCpuTimeCannotBeMeasured() {
        final ServerThreadCpu unsupported = new ServerThreadCpu(thread -> -1L, () -> this.gcPauseMillis, () -> this.nowMillis);
        unsupported.observe(1L, this.serverThread);
        this.nowMillis += 10_000L;

        assertNull(unsupported.describe(this.serverThread));
    }

    @Test
    void nothingIsReportedBeforeTheFirstObservation() {
        assertNull(this.cpu.describe(this.serverThread));
    }

    @Test
    void theRealMeasurementWorksForTheCurrentThread() {
        final ServerThreadCpu real = new ServerThreadCpu();
        real.observe(1L, this.serverThread);
        // Burn a little CPU so that the clock moves
        long sum = 0L;
        final long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30);
        while (System.nanoTime() < end) {
            sum += System.nanoTime() % 7L;
        }
        assertTrue(sum >= 0L);

        // Null only on JVMs without thread CPU time support; HotSpot on Linux supports it
        final String description = real.describe(this.serverThread);
        assertNotNull(description);
        assertTrue(description.startsWith("The server thread used "), description);
    }

    @Test
    void theRealMeasurementTellsASpinningThreadFromASleepingOne() throws InterruptedException {
        final ServerThreadCpu spinningCpu = new ServerThreadCpu();
        final ServerThreadCpu sleepingCpu = new ServerThreadCpu();
        final long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
        final Thread spinning = new Thread(() -> {
            long sum = 0L;
            while (System.nanoTime() < end) {
                sum += System.nanoTime() % 7L;
            }
            assertTrue(sum >= 0L);
        }, "Spinning thread");
        final Thread sleeping = new Thread(() -> {
            try {
                Thread.sleep(TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()));
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "Sleeping thread");
        spinning.start();
        sleeping.start();
        try {
            spinningCpu.observe(1L, spinning);
            sleepingCpu.observe(1L, sleeping);
            Thread.sleep(300);

            final String spinningDescription = spinningCpu.describe(spinning);
            final String sleepingDescription = sleepingCpu.describe(sleeping);

            assertNotNull(spinningDescription);
            assertNotNull(sleepingDescription);
            // Generous limits, so that a loaded machine does not make the test fail
            assertTrue(percent(spinningDescription) >= 50, spinningDescription);
            assertTrue(percent(sleepingDescription) <= ServerThreadCpu.WAITING_PERCENT, sleepingDescription);
        } finally {
            spinning.join();
            sleeping.join();
        }
    }
}
