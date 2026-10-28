package studio.cubiq.configcheck;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import studio.cubiq.Cubiq;

/**
 * Reports settings in {@code bukkit.yml}, {@code spigot.yml} and {@code purpur.yml} that have no effect: unknown
 * (usually misspelled) settings and values of the wrong type.
 *
 * <p>The check runs once the server has started, when the configurations of all worlds have been read. It only
 * logs problems and never changes a file. Set the system property {@value #ENABLED_PROPERTY} to {@code false} to
 * disable it.</p>
 */
@NullMarked
public final class ConfigurationCheck {

    static final String ENABLED_PROPERTY = "cubiq.configCheck";
    static final int MAX_LOGGED_FINDINGS = 50;

    private static final Logger LOGGER = Cubiq.LOGGER;
    private static final ConfigurationChecker.Scope WORLD_SETTINGS = new ConfigurationChecker.Scope("world-settings", "default");
    private static final ConfigurationChecker.Scope BUKKIT_WORLDS = new ConfigurationChecker.Scope("worlds", "*");
    private static final Map<String, TrackedYamlConfiguration> CONFIGURATIONS = new ConcurrentHashMap<>();

    /**
     * Whether the check is still going to run. Configurations only record their usage until then, so that nothing
     * is recorded when the check is disabled, and configurations created later (for example by {@code /reload})
     * neither record nor stay registered.
     */
    private static volatile boolean pending = isEnabled();

    /**
     * Settings the server uses although their tracked configuration may never read them: they are read from a
     * separate copy of the file before the tracked configuration exists, or only under conditions that may not hold.
     */
    private static final Map<String, Set<String>> ALSO_USED = Map.of(
        // net.minecraft.server.Main reads world-container from its own copy of bukkit.yml to locate the worlds;
        // CraftServer reads the per-world generator settings through child sections, which are not tracked
        "bukkit.yml", Set.of("settings.world-container", "worlds.*.generator", "worlds.*.biome-provider"),
        // PurpurWorldConfig skips reading it while the PURPUR_FORCE_IDLE_KICK environment variable is set
        "purpur.yml", Set.of("world-settings.default.gameplay-mechanics.player.idle-timeout.kick-if-idle")
    );

    private ConfigurationCheck() {
    }

    private static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
    }

    static boolean isPending() {
        return pending;
    }

    static void register(final TrackedYamlConfiguration configuration) {
        if (pending) {
            CONFIGURATIONS.put(configuration.name(), configuration);
        }
    }

    /**
     * Restores the state before the server started, so that tests can run the check more than once.
     */
    static void resetForTests() {
        pending = isEnabled();
        CONFIGURATIONS.clear();
        PaperConfigurationCheck.resetForTests();
    }

    static ConfigurationChecker.@Nullable Scope scopeOf(final String name) {
        return switch (name) {
            case "spigot.yml", "purpur.yml" -> WORLD_SETTINGS;
            case "bukkit.yml" -> BUKKIT_WORLDS;
            default -> null;
        };
    }

    /**
     * Checks all tracked configurations and logs the result. Called once the server has finished starting.
     */
    public static void runAfterStartup() {
        if (!pending) {
            return;
        }
        pending = false;
        final List<TrackedYamlConfiguration> configurations = new ArrayList<>(CONFIGURATIONS.values());
        CONFIGURATIONS.clear();
        configurations.forEach(TrackedYamlConfiguration::stopRecording);
        try {
            final long start = System.nanoTime();
            configurations.sort(Comparator.comparing(TrackedYamlConfiguration::name));
            final List<ConfigurationFinding> findings = new ArrayList<>();
            final List<String> checked = new ArrayList<>();
            for (final TrackedYamlConfiguration configuration : configurations) {
                final File file = configuration.file();
                if (file == null || !file.isFile()) {
                    continue;
                }
                final List<ConfigurationFinding> fileFindings = check(configuration, file);
                if (fileFindings == null) {
                    LOGGER.warn("Configuration check: could not read {}, so it was not checked", file.getPath());
                } else {
                    findings.addAll(fileFindings);
                    checked.add(file.getPath());
                }
            }
            // Paper's files were checked when Paper loaded them
            for (final PaperConfigurationCheck.Result result : PaperConfigurationCheck.drain()) {
                if (result.findings() == null) {
                    LOGGER.warn("Configuration check: could not check {}", result.file());
                } else {
                    findings.addAll(result.findings());
                    checked.add(result.file());
                }
            }
            // Including the time it took to check Paper's files while they were loaded
            final long elapsedMillis = (System.nanoTime() - start + PaperConfigurationCheck.drainCheckNanos()) / 1_000_000L;
            for (final String line : report(checked, findings, elapsedMillis)) {
                if (findings.isEmpty()) {
                    LOGGER.info(line);
                } else {
                    LOGGER.warn(line);
                }
            }
        } catch (final RuntimeException | LinkageError ex) {
            // A bug in this diagnostic must never affect the server
            LOGGER.warn("The configuration check failed", ex);
        } finally {
            configurations.forEach(TrackedYamlConfiguration::releaseUsage);
        }
    }

    /**
     * Checks one configuration file.
     *
     * @return the problems found, or {@code null} if the file could not be read
     */
    static @Nullable List<ConfigurationFinding> check(final TrackedYamlConfiguration configuration, final File file) {
        final YamlConfiguration contents = new YamlConfiguration();
        try {
            contents.load(file);
        } catch (final IOException | InvalidConfigurationException ex) {
            LOGGER.debug("Could not read {} for the configuration check", file, ex);
            return null;
        }
        final Map<String, Integer> usage = new HashMap<>(configuration.usage().snapshot());
        for (final String path : ALSO_USED.getOrDefault(configuration.name(), Set.of())) {
            usage.merge(path, ConfigurationUsage.PROBE, (previous, added) -> previous | added);
        }
        final ConfigurationChecker checker = new ConfigurationChecker(
            file.getPath(), usage, configuration.getDefaults(), scopeOf(configuration.name())
        );
        return checker.check(contents);
    }

    /**
     * Formats the result of a check as log lines.
     *
     * @param checked       the files that were checked
     * @param findings      the problems found in them
     * @param elapsedMillis how long the check took
     * @return the lines to log
     */
    static List<String> report(final List<String> checked, final List<ConfigurationFinding> findings, final long elapsedMillis) {
        final List<String> lines = new ArrayList<>();
        final String files = String.join(", ", checked) + " (" + elapsedMillis + " ms)";
        if (findings.isEmpty()) {
            if (!checked.isEmpty()) {
                lines.add("Configuration check: no unknown settings or invalid values in " + files);
            }
            return lines;
        }
        lines.add("Configuration check: found " + findings.size() + (findings.size() == 1 ? " problem" : " problems")
            + " in " + files + ". The following settings have no effect:");
        for (int i = 0; i < findings.size() && i < MAX_LOGGED_FINDINGS; i++) {
            final ConfigurationFinding finding = findings.get(i);
            lines.add("  " + finding.file() + ": " + finding.message());
        }
        if (findings.size() > MAX_LOGGED_FINDINGS) {
            lines.add("  ... and " + (findings.size() - MAX_LOGGED_FINDINGS) + " more");
        }
        lines.add("Fix or remove these settings. This check runs on every start and can be disabled with -D" + ENABLED_PROPERTY + "=false");
        return lines;
    }
}
