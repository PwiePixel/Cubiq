package studio.cubiq.watchdog;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerThreadCpuTest {

    private long cpuNanos;
    private long nowMillis = 100_000L;
    private final ServerThreadCpu cpu = new ServerThreadCpu(thread -> this.cpuNanos, () -> this.nowMillis);
    private final Thread serverThread = Thread.currentThread();

    private void advance(final long millis, final long cpuMillis) {
        this.nowMillis += millis;
        this.cpuNanos += TimeUnit.MILLISECONDS.toNanos(cpuMillis);
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
        assertTrue(description.endsWith("(0%), so it is mostly waiting, for example for a lock, a file, the network or a database."), description);
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
        final ServerThreadCpu unsupported = new ServerThreadCpu(thread -> -1L, () -> this.nowMillis);
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
}
