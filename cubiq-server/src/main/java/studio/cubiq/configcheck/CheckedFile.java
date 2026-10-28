package studio.cubiq.configcheck;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The result of checking a configuration file that was checked when the server loaded it.
 *
 * @param file     the file, relative to the server directory if it is inside it
 * @param findings the problems found, or {@code null} if the file could not be checked
 */
@NullMarked
record CheckedFile(String file, @Nullable List<ConfigurationFinding> findings) {

    /**
     * Names a file for the report of the check: relative to the server directory if it is inside it.
     */
    static String displayName(final Path file) {
        final Path absolute = file.toAbsolutePath().normalize();
        final Path serverDirectory = Path.of("").toAbsolutePath();
        return (absolute.startsWith(serverDirectory) ? serverDirectory.relativize(absolute) : absolute).toString();
    }
}
