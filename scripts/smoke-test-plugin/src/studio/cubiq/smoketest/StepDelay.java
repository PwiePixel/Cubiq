package studio.cubiq.smoketest;

/**
 * Makes a lifecycle step of the test plugin take as long as the {@value #PROPERTY} system property says.
 */
final class StepDelay {

    static final String PROPERTY = "cubiqSmokeTest.stepDelayMillis";

    private StepDelay() {
    }

    static void waitInStep() {
        final long millis = Long.getLong(PROPERTY, 0L);
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
