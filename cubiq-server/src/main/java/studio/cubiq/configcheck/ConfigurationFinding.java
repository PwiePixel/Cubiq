package studio.cubiq.configcheck;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A problem found in a configuration file.
 *
 * @param file       the configuration file
 * @param type       the kind of problem
 * @param path       the path of the setting or section in the file
 * @param settings   the number of settings affected; greater than one for unknown sections
 * @param suggestion the setting that was probably meant, as a path in the same file, if one was found
 * @param expected   for invalid values, a description of the expected value
 * @param actual     for invalid values, a description of the configured value
 */
@NullMarked
public record ConfigurationFinding(
    String file,
    Type type,
    String path,
    int settings,
    @Nullable String suggestion,
    @Nullable String expected,
    @Nullable String actual
) {

    public enum Type {
        /** A setting that the server does not use. */
        UNKNOWN_SETTING,
        /** A section, including all settings below it, that the server does not use. */
        UNKNOWN_SECTION,
        /** A setting the server uses, but whose value has the wrong type and is therefore ignored. */
        INVALID_VALUE
    }

    static ConfigurationFinding unknownSetting(final String file, final String path, final @Nullable String suggestion) {
        return new ConfigurationFinding(file, Type.UNKNOWN_SETTING, path, 1, suggestion, null, null);
    }

    static ConfigurationFinding unknownSection(final String file, final String path, final int settings, final @Nullable String suggestion) {
        return new ConfigurationFinding(file, Type.UNKNOWN_SECTION, path, settings, suggestion, null, null);
    }

    static ConfigurationFinding invalidValue(final String file, final String path, final String expected, final String actual) {
        return new ConfigurationFinding(file, Type.INVALID_VALUE, path, 1, null, expected, actual);
    }

    /**
     * Describes the problem for server owners.
     *
     * @return a single line message, without the file name
     */
    public String message() {
        final String didYouMean = this.suggestion == null ? "" : " Did you mean '" + this.suggestion + "'?";
        return switch (this.type) {
            case UNKNOWN_SETTING -> "Unknown setting '" + this.path + "' has no effect." + didYouMean;
            case UNKNOWN_SECTION -> "Unknown section '" + this.path + "' (" + this.settings
                + (this.settings == 1 ? " setting" : " settings") + ") has no effect." + didYouMean;
            case INVALID_VALUE -> "'" + this.path + "' must be " + this.expected + ", but is " + this.actual + ", so it is ignored.";
        };
    }
}
