package studio.cubiq.lifecycle;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A clock for tests that only moves when told to.
 */
final class FakeClock implements LongSupplier {

    private volatile long nanos = 1_000_000_000L;

    @Override
    public long getAsLong() {
        return this.nanos;
    }

    void advanceMillis(final long millis) {
        this.nanos += TimeUnit.MILLISECONDS.toNanos(millis);
    }

    void advanceSeconds(final long seconds) {
        this.nanos += TimeUnit.SECONDS.toNanos(seconds);
    }
}
