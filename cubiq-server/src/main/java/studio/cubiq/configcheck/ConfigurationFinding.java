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
 * @param suggestion for unknown settings, the setting that was probably meant, as a path in the same file, if one was
 *                   found; for replaced settings, what replaces them
 * @param expected   for invalid values, a description of the expected value
 * @param actual     for invalid values, a description of the configured value
 * @param used       for invalid values, the value that the server uses instead, if it is known; otherwise the server
 *                   ignores the value
 * @param note       a sentence that explains the finding further, if any
 */
@NullMarked
public record ConfigurationFinding(
    String file,
    Type type,
    String path,
    int settings,
    @Nullable String suggestion,
    @Nullable String expected,
    @Nullable String actual,
    @Nullable String used,
    @Nullable String note
) {

    public enum Type {
        /** A setting that the server does not use. */
        UNKNOWN_SETTING,
        /** A section, including all settings below it, that the server does not use. */
        UNKNOWN_SECTION,
        /** A setting that the server no longer uses, because another setting replaced it. */
        REPLACED_SETTING,
        /** A setting the server uses, but whose value it cannot use as it is written. */
        INVALID_VALUE
    }

    static ConfigurationFinding unknownSetting(final String file, final String path, final @Nullable String suggestion) {
        return new ConfigurationFinding(file, Type.UNKNOWN_SETTING, path, 1, suggestion, null, null, null, null);
    }

    static ConfigurationFinding unknownSection(final String file, final String path, final int settings, final @Nullable String suggestion) {
        return new ConfigurationFinding(file, Type.UNKNOWN_SECTION, path, settings, suggestion, null, null, null, null);
    }

    static ConfigurationFinding replacedSetting(final String file, final String path, final String replacement) {
        return new ConfigurationFinding(file, Type.REPLACED_SETTING, path, 1, replacement, null, null, null, null);
    }

    static ConfigurationFinding invalidValue(final String file, final String path, final String expected, final String actual) {
        return new ConfigurationFinding(file, Type.INVALID_VALUE, path, 1, null, expected, actual, null, null);
    }

    static ConfigurationFinding invalidValue(final String file, final String path, final String expected, final String actual, final String used) {
        return new ConfigurationFinding(file, Type.INVALID_VALUE, path, 1, null, expected, actual, used, null);
    }

    /**
     * Returns this finding with a sentence that explains it further.
     */
    ConfigurationFinding withNote(final String note) {
        return new ConfigurationFinding(this.file, this.type, this.path, this.settings, this.suggestion, this.expected, this.actual, this.used, note);
    }

    /**
     * Describes the problem for server owners.
     *
     * @return a single line message, without the file name
     */
    public String message() {
        final String didYouMean = this.suggestion == null ? "" : " Did you mean '" + this.suggestion + "'?";
        final String message = switch (this.type) {
            case UNKNOWN_SETTING -> "Unknown setting '" + this.path + "' has no effect." + didYouMean;
            case UNKNOWN_SECTION -> "Unknown section '" + this.path + "' (" + this.settings
                + (this.settings == 1 ? " setting" : " settings") + ") has no effect." + didYouMean;
            case REPLACED_SETTING -> "Setting '" + this.path + "' has no effect anymore. Use " + this.suggestion + " instead.";
            case INVALID_VALUE -> "'" + this.path + "' must be " + this.expected + ", but is " + this.actual
                + (this.used == null ? ", so it is ignored." : ", so the server uses " + this.used + ".");
        };
        return this.note == null ? message : message + " " + this.note;
    }
}
