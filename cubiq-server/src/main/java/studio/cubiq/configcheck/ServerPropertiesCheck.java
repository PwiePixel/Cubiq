package studio.cubiq.configcheck;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import studio.cubiq.Cubiq;

/**
 * Finds settings in {@code server.properties} that do not work as written: settings that Minecraft does not read,
 * for example because they are misspelled or were replaced by game rules, and values that Minecraft does not use as
 * they are written.
 *
 * <p>When Minecraft has loaded the file, {@link #loaded} compares its settings with the settings that Minecraft read
 * and the values it could not use, which {@link PropertiesUsage} recorded. Minecraft then writes the file again, with
 * the values it uses and the settings it does not know. When Paper updates {@code paper-global.yml} from an older
 * version while the server starts, it moves the value of {@code allow-nether} to its own configuration and removes the
 * setting from the file, so it is not reported. Findings for other settings that are removed from the file while the
 * server starts, such as legacy settings, are still reported, with a note, because their values are lost.</p>
 */
@NullMarked
public final class ServerPropertiesCheck {

    private static final Logger LOGGER = Cubiq.LOGGER;
    /** Lines that are not settings, such as a pasted token, become keys of any length when Properties loads them. */
    private static final int MAX_KEY_LENGTH = 80;
    private static final char BYTE_ORDER_MARK = '\uFEFF';
    private static final String BYTE_ORDER_MARK_NOTE = "The name starts with an invisible byte order mark, which some editors put at the "
        + "start of a file. Remove it.";
    private static final String REMOVED_NOTE = "The server removed it from the file while starting.";

    /**
     * Settings that Minecraft or Paper read from {@code server.properties} in earlier versions, and what replaces them.
     */
    static final Map<String, String> REPLACED = Map.of(
        "allow-nether", "misc.enable-nether in paper-global.yml",
        "enable-command-block", "the game rule command_blocks_work",
        "pvp", "the game rule pvp",
        "spawn-monsters", "the game rule spawn_monsters"
    );

    /**
     * Settings whose value Paper moves to its own configuration when it updates {@code paper-global.yml} from a version
     * before the change, removing them from {@code server.properties}.
     */
    static final Set<String> MOVED_BY_PAPER = Set.of("allow-nether");

    /** The loaded files by their name, in the order they were loaded. */
    private static final Map<String, Loaded> LOADED = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * A loaded file and its findings, by the setting they are about.
     */
    private record Loaded(Path file, String name, Map<String, ConfigurationFinding> findings) {
    }

    private ServerPropertiesCheck() {
    }

    /**
     * Called by Minecraft when it has loaded {@code server.properties}. Never throws.
     *
     * @param file       the file
     * @param properties the settings of the file, after Minecraft has read them
     * @param usage      how Minecraft read the settings
     */
    public static void loaded(final Path file, final Properties properties, final PropertiesUsage usage) {
        if (!ConfigurationCheck.isPending()) {
            return;
        }
        final long start = System.nanoTime();
        try {
            final String name = CheckedFile.displayName(file);
            // A /reload before the check loads the file again, after Minecraft wrote the values it uses to it, so the
            // first load is the one that shows the values as the server owner wrote them
            if (!LOADED.containsKey(name)) {
                LOADED.put(name, new Loaded(file, name, findings(name, properties, usage)));
            }
        } catch (final RuntimeException | LinkageError ex) {
            // A bug in this diagnostic must never affect loading the settings
            LOGGER.debug("The configuration check of {} failed", file, ex);
        } finally {
            ConfigurationCheck.addCheckTime(System.nanoTime() - start);
        }
    }

    /**
     * Compares the settings of a loaded file with how Minecraft read them.
     *
     * @param file       the name of the file, for the findings
     * @param properties the settings of the file, after Minecraft has read them; Minecraft adds the settings it read
     *                   but did not find, and replaces the values it read with the values it uses
     * @param usage      how Minecraft read the settings
     * @return the findings by the setting they are about, ordered by the setting
     */
    static Map<String, ConfigurationFinding> findings(final String file, final Properties properties, final PropertiesUsage usage) {
        final Set<String> readKeys = usage.readKeys();
        final Map<String, PropertiesUsage.Misread> misreads = usage.misreads();
        final Map<String, ConfigurationFinding> findings = new LinkedHashMap<>();
        // Minecraft does not keep the order of the settings, so they are reported in alphabetical order. Minecraft
        // removes legacy settings from the properties when it reads them, so their values are only in the misreads.
        final Set<String> keys = new TreeSet<>(keys(properties));
        keys.addAll(misreads.keySet());
        for (final String key : keys) {
            if (readKeys.contains(key)) {
                final PropertiesUsage.Misread misread = misreads.get(key);
                final ConfigurationFinding finding = misread == null ? null : invalidValue(file, key, misread, properties.getProperty(key));
                if (finding != null) {
                    findings.put(key, finding);
                }
                continue;
            }
            final String replacement = REPLACED.get(key);
            ConfigurationFinding finding = replacement != null
                ? ConfigurationFinding.replacedSetting(file, key, replacement)
                : ConfigurationFinding.unknownSetting(file, path(key), suggest(key, readKeys));
            if (!key.isEmpty() && key.charAt(0) == BYTE_ORDER_MARK) {
                finding = finding.withNote(BYTE_ORDER_MARK_NOTE);
            }
            findings.put(key, finding);
        }
        return findings;
    }

    /**
     * Describes a value that Minecraft did not use as written.
     *
     * @param written the value as Minecraft wrote it back to the file, or {@code null} for a legacy setting
     * @return the finding, or {@code null} if the server uses what the value means, for example the default value
     *     {@code easy} for {@code difficulty=Easy}, which Minecraft cannot read because names are case-sensitive
     */
    private static @Nullable ConfigurationFinding invalidValue(final String file, final String key, final PropertiesUsage.Misread misread, final @Nullable String written) {
        final String used = written != null ? written : misread.converted();
        if (used == null) {
            return ConfigurationFinding.invalidValue(file, path(key), misread.expected(), describe(misread.value()));
        }
        if (misread.defaultUsed() && misread.value().strip().equalsIgnoreCase(used)) {
            return null;
        }
        // Settings such as op-permission-level have a type of their own, which Minecraft writes as a number
        final String expected = misread.expected().equals(PropertiesUsage.ANY_VALUE) && PropertiesUsage.isInteger(used)
            ? PropertiesUsage.WHOLE_NUMBER
            : misread.expected();
        return ConfigurationFinding.invalidValue(
            file, path(key), expected, describe(misread.value()), misread.defaultUsed() ? "the default value " + printable(used) : printable(used)
        );
    }

    /**
     * Removes and returns the results of the files loaded so far. Findings for settings that the server removed from
     * the file while starting get a note, except for the settings that Paper moved to its own configuration.
     *
     * @return the results, in the order the files were loaded
     */
    static List<CheckedFile> drain() {
        final List<Loaded> loaded;
        synchronized (LOADED) {
            loaded = new ArrayList<>(LOADED.values());
            LOADED.clear();
        }
        final List<CheckedFile> results = new ArrayList<>();
        for (final Loaded file : loaded) {
            final @Nullable Set<String> keys = keysInFile(file.file());
            final List<ConfigurationFinding> findings = new ArrayList<>();
            for (final Map.Entry<String, ConfigurationFinding> finding : file.findings().entrySet()) {
                // Report everything as it is if the file cannot be read again
                if (keys == null || keys.contains(finding.getKey())) {
                    findings.add(finding.getValue());
                } else if (!MOVED_BY_PAPER.contains(finding.getKey())) {
                    findings.add(finding.getValue().withNote(REMOVED_NOTE));
                }
            }
            results.add(new CheckedFile(file.name(), findings));
        }
        return results;
    }

    static void resetForTests() {
        LOADED.clear();
    }

    private static @Nullable String suggest(final String key, final Set<String> readKeys) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (final String candidate : new TreeSet<>(readKeys)) {
            final int distance = KeySimilarity.similarity(key, candidate);
            if (distance >= 0 && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static Set<String> keys(final Properties properties) {
        // Only the settings of the file itself, not of any defaults
        return properties.keySet().stream().map(String::valueOf).collect(Collectors.toSet());
    }

    /**
     * Reads the settings that a file contains, like Minecraft reads it: as UTF-8 and, if that fails, as ISO 8859-1.
     *
     * @return the settings, an empty set if the file does not exist, or {@code null} if it cannot be read
     */
    static @Nullable Set<String> keysInFile(final Path file) {
        if (!Files.exists(file)) {
            return Set.of();
        }
        try {
            try (InputStream input = Files.newInputStream(file)) {
                return keys(load(new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT))));
            } catch (final CharacterCodingException ex) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
                    return keys(load(reader));
                }
            }
        } catch (final IOException | IllegalArgumentException ex) {
            LOGGER.debug("Could not read {} again for the configuration check", file, ex);
            return null;
        }
    }

    private static Properties load(final Reader reader) throws IOException {
        final Properties properties = new Properties();
        properties.load(reader);
        return properties;
    }

    /**
     * Names a setting of the file for a finding.
     */
    static String path(final String key) {
        final String printable = printable(key);
        return printable.length() <= MAX_KEY_LENGTH ? printable : printable.substring(0, MAX_KEY_LENGTH - 3) + "...";
    }

    /**
     * Describes a value of the file for a finding.
     */
    static String describe(final String value) {
        final String description = ConfigurationChecker.describe(printable(value));
        return !value.isEmpty() && isSpace(value.charAt(value.length() - 1))
            ? description + " (with whitespace at the end)"
            : description;
    }

    private static boolean isSpace(final char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }

    /**
     * Escapes control characters, which a file can contain as escape sequences, so that each finding stays on one
     * line of the log, and characters that are invisible or look like a space, such as a byte order mark or a
     * no-break space, so that they can be seen.
     */
    static String printable(final String text) {
        final StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char character = text.charAt(i);
            switch (character) {
                case '\t' -> builder.append("\\t");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                default -> {
                    if (Character.isISOControl(character) || Character.getType(character) == Character.FORMAT
                        || (Character.isSpaceChar(character) && character != ' ') || !Character.isDefined(character)) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.toString();
    }
}
