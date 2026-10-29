package studio.cubiq;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Project-wide constants for Cubiq.
 */
@NullMarked
public final class Cubiq {

    /**
     * Logger for messages that Cubiq shows to server owners. The console prints its name, {@code [Cubiq]}, in front
     * of each message.
     */
    public static final Logger LOGGER = LoggerFactory.getLogger("Cubiq");

    /**
     * The canonical source repository of Cubiq.
     */
    public static final String REPOSITORY_URL = "https://github.com/PwiePixel/Cubiq";

    /**
     * Where problems with Cubiq should be reported.
     */
    public static final String ISSUES_URL = REPOSITORY_URL + "/issues";

    /**
     * Where Cubiq's releases are published.
     */
    public static final String RELEASES_URL = REPOSITORY_URL + "/releases";

    private Cubiq() {
    }
}
