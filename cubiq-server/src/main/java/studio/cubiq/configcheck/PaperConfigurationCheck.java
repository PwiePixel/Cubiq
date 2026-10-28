package studio.cubiq.configcheck;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import studio.cubiq.Cubiq;

/**
 * Finds settings that have no effect in Paper's configuration files: {@code config/paper-global.yml},
 * {@code config/paper-world-defaults.yml} and the {@code paper-world.yml} of each world.
 *
 * <p>Paper maps these files to configuration objects and writes the settings it knows back to the file, but it
 * leaves settings it does not know in the file and ignores them, so a misspelled setting has no effect without any
 * message. When Paper has loaded a file, {@link #loaded} writes the loaded configuration to an empty node with
 * Paper's own serializers, which gives exactly the settings Paper knows, including the entries of maps that it could
 * read. Settings in the file that are not part of that tree have no effect. {@link ConfigurationCheck} reports them
 * together with the problems in the other files.</p>
 *
 * <p>Paper reports values of the wrong type itself, because it cannot load such a file.</p>
 */
public final class PaperConfigurationCheck {

    private static final Logger LOGGER = Cubiq.LOGGER;
    private static final String VERSION_FIELD = "_version";
    private static final String MINECRAFT_NAMESPACE = "minecraft:";
    private static final List<Result> RESULTS = new CopyOnWriteArrayList<>();
    /** How long checking the files took, so that the report of the check can include it. */
    private static final AtomicLong CHECK_NANOS = new AtomicLong();

    /**
     * The result of checking one file.
     *
     * @param file     the file, relative to the server directory if it is inside it
     * @param findings the settings without effect, or {@code null} if the file could not be checked
     */
    record Result(String file, @Nullable List<ConfigurationFinding> findings) {
    }

    private PaperConfigurationCheck() {
    }

    /**
     * Called by Paper when it has loaded one of its configuration files. Never throws.
     *
     * @param file          the file
     * @param fileNode      the settings in the file, after Paper's transformations; for world files without the
     *                      settings of the world defaults
     * @param type          the type of the configuration
     * @param configuration the configuration that Paper loaded from the file
     */
    public static void loaded(final Path file, final ConfigurationNode fileNode, final Type type, final Object configuration) {
        if (!ConfigurationCheck.isPending()) {
            return;
        }
        final long start = System.nanoTime();
        try {
            final String name = displayName(file);
            List<ConfigurationFinding> findings;
            try {
                final ConfigurationNode known = BasicConfigurationNode.root(fileNode.options());
                known.set(type, configuration);
                findings = unknownSettings(name, fileNode, known);
            } catch (final SerializationException | RuntimeException ex) {
                LOGGER.debug("Could not check {}", name, ex);
                findings = null;
            }
            RESULTS.add(new Result(name, findings));
        } catch (final RuntimeException | LinkageError ex) {
            // A bug in this diagnostic must never affect loading the configuration
            LOGGER.debug("The configuration check of {} failed", file, ex);
        } finally {
            CHECK_NANOS.addAndGet(System.nanoTime() - start);
        }
    }

    /**
     * Removes and returns the results of the files checked so far.
     *
     * @return the results, in the order the files were loaded
     */
    static List<Result> drain() {
        final List<Result> results = new ArrayList<>(RESULTS);
        RESULTS.removeAll(results);
        return results;
    }

    /**
     * Removes and returns how long checking the files took so far.
     *
     * @return the time in nanoseconds
     */
    static long drainCheckNanos() {
        return CHECK_NANOS.getAndSet(0L);
    }

    static void resetForTests() {
        RESULTS.clear();
        CHECK_NANOS.set(0L);
    }

    /**
     * Compares the settings in a file with the settings Paper knows.
     *
     * @param file     the name of the file, for the findings
     * @param fileNode the settings in the file
     * @param known    the settings Paper knows, as written by Paper's serializers
     * @return the settings and sections in the file that Paper does not know
     */
    static List<ConfigurationFinding> unknownSettings(final String file, final ConfigurationNode fileNode, final ConfigurationNode known) {
        final List<ConfigurationFinding> findings = new ArrayList<>();
        new Comparison(file, known).compare("", fileNode, known, findings);
        return findings;
    }

    private static final class Comparison {

        private final String file;
        private final ConfigurationNode root;
        /** Paths of the known settings by their normalized last element, for suggesting settings put in the wrong place. */
        private @Nullable Map<String, List<String>> knownPathsByName;

        private Comparison(final String file, final ConfigurationNode root) {
            this.file = file;
            this.root = root;
        }

        private void compare(final String prefix, final ConfigurationNode fileNode, final ConfigurationNode known, final List<ConfigurationFinding> findings) {
            if (!fileNode.isMap() || !known.isMap()) {
                return;
            }
            final Map<String, ConfigurationNode> knownChildren = new HashMap<>();
            final Map<String, ConfigurationNode> normalizedKnownChildren = new HashMap<>();
            for (final Map.Entry<Object, ? extends ConfigurationNode> entry : known.childrenMap().entrySet()) {
                knownChildren.put(String.valueOf(entry.getKey()), entry.getValue());
                normalizedKnownChildren.putIfAbsent(normalize(String.valueOf(entry.getKey())), entry.getValue());
            }
            for (final Map.Entry<Object, ? extends ConfigurationNode> entry : fileNode.childrenMap().entrySet()) {
                final String key = String.valueOf(entry.getKey());
                if (prefix.isEmpty() && key.equals(VERSION_FIELD)) {
                    continue;
                }
                final String path = prefix.isEmpty() ? key : prefix + "." + key;
                final ConfigurationNode child = entry.getValue();
                final ConfigurationNode knownChild = knownChildren.getOrDefault(key, normalizedKnownChildren.get(normalize(key)));
                if (knownChild != null) {
                    this.compare(path, child, knownChild, findings);
                    continue;
                }
                final int settings = settings(child);
                final String suggestion = this.suggest(prefix, key, knownChildren);
                findings.add(child.isMap() && settings > 0
                    ? ConfigurationFinding.unknownSection(this.file, path, settings, suggestion)
                    : ConfigurationFinding.unknownSetting(this.file, path, suggestion));
            }
        }

        private @Nullable String suggest(final String prefix, final String key, final Map<String, ConfigurationNode> siblings) {
            String best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (final String sibling : siblings.keySet()) {
                final int distance = KeySimilarity.similarity(key, sibling);
                if (distance >= 0 && distance < bestDistance) {
                    best = sibling;
                    bestDistance = distance;
                }
            }
            if (best != null) {
                return prefix.isEmpty() ? best : prefix + "." + best;
            }
            // The setting may have been put into the wrong section; suggest it when its name is unambiguous
            final List<String> elsewhere = this.knownPathsByName().getOrDefault(KeySimilarity.normalize(key), List.of());
            return elsewhere.size() == 1 ? elsewhere.getFirst() : null;
        }

        private Map<String, List<String>> knownPathsByName() {
            if (this.knownPathsByName == null) {
                this.knownPathsByName = new HashMap<>();
                collectPaths("", this.root, this.knownPathsByName);
            }
            return this.knownPathsByName;
        }

        private static void collectPaths(final String prefix, final ConfigurationNode node, final Map<String, List<String>> paths) {
            for (final Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
                final String key = String.valueOf(entry.getKey());
                final String path = prefix.isEmpty() ? key : prefix + "." + key;
                paths.computeIfAbsent(KeySimilarity.normalize(key), name -> new ArrayList<>()).add(path);
                collectPaths(path, entry.getValue(), paths);
            }
        }
    }

    /**
     * Normalizes a key for comparison: maps of registry entries accept keys with and without the {@code minecraft}
     * namespace, and Paper writes them back in one of the two forms.
     */
    static String normalize(final String key) {
        final String lowerCase = key.toLowerCase(Locale.ROOT);
        return lowerCase.startsWith(MINECRAFT_NAMESPACE) ? lowerCase.substring(MINECRAFT_NAMESPACE.length()) : lowerCase;
    }

    private static int settings(final ConfigurationNode node) {
        if (!node.isMap()) {
            return 1;
        }
        int settings = 0;
        for (final ConfigurationNode child : node.childrenMap().values()) {
            settings += settings(child);
        }
        return settings;
    }

    static String displayName(final Path file) {
        final Path absolute = file.toAbsolutePath().normalize();
        final Path serverDirectory = Path.of("").toAbsolutePath();
        return (absolute.startsWith(serverDirectory) ? serverDirectory.relativize(absolute) : absolute).toString();
    }
}
