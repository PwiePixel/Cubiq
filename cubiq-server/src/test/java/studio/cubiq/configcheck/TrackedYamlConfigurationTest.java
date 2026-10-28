package studio.cubiq.configcheck;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackedYamlConfigurationTest {

    private static final String YAML = """
        settings:
          debug: true
          timeout: 30
          name: test
          worlds:
          - world
        stats:
          forced:
            jumps: 3
        """;

    private static TrackedYamlConfiguration load() throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = new TrackedYamlConfiguration("test.yml");
        config.loadFromString(YAML);
        return config;
    }

    private static Map<String, Integer> usage(final TrackedYamlConfiguration config) {
        return config.usage().snapshot();
    }

    @Test
    void loadingIsNotRecorded() throws InvalidConfigurationException {
        assertEquals(Map.of(), usage(load()));
    }

    @Test
    void typedReadsRecordTheExpectedType() throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = load();

        assertTrue(config.getBoolean("settings.debug", false));
        assertEquals(30, config.getInt("settings.timeout", 1));
        assertEquals("test", config.getString("settings.name", "default"));
        assertEquals(List.of("world"), config.getList("settings.worlds", List.of()));
        config.getDouble("settings.ratio", 0.5D);

        assertEquals(Map.of(
            "settings.debug", ConfigurationUsage.READ_BOOLEAN,
            "settings.timeout", ConfigurationUsage.READ_NUMBER,
            "settings.name", ConfigurationUsage.READ_TEXT,
            "settings.worlds", ConfigurationUsage.READ_LIST,
            "settings.ratio", ConfigurationUsage.READ_NUMBER
        ), usage(config));
    }

    @Test
    void resolvingNestedPathsDoesNotRecordTheirSections() throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = load();

        config.getInt("stats.forced.jumps", 0);

        assertEquals(Map.of("stats.forced.jumps", ConfigurationUsage.READ_NUMBER), usage(config));
    }

    @Test
    void singleArgumentGettersRecordTheirType() throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = load();

        config.getBoolean("settings.debug");
        config.getInt("settings.timeout");
        config.getString("settings.name");
        config.getStringList("settings.worlds");

        assertEquals(Map.of(
            "settings.debug", ConfigurationUsage.READ_BOOLEAN,
            "settings.timeout", ConfigurationUsage.READ_NUMBER,
            "settings.name", ConfigurationUsage.READ_TEXT,
            "settings.worlds", ConfigurationUsage.READ_LIST
        ), usage(config));
    }

    @Test
    void lookupsWithoutTypeAreProbes() throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = load();

        assertTrue(config.contains("settings.debug"));
        assertTrue(config.isSet("settings.name"));
        assertNotNull(config.get("settings.timeout"));

        assertEquals(Map.of(
            "settings.debug", ConfigurationUsage.PROBE,
            "settings.name", ConfigurationUsage.PROBE,
            "settings.timeout", ConfigurationUsage.PROBE
        ), usage(config));
    }

    @Test
    void directGetWithFallbackDoesNotDeriveATypeFromTheFallback() throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = load();

        // Like SpigotWorldConfig#get, which passes the value of another setting as the fallback
        config.get("settings.name", 10);

        assertEquals(Map.of("settings.name", ConfigurationUsage.PROBE), usage(config));
    }

    @Test
    void commentsAreNotUsage() throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = load();

        config.getComments("stats.forced.jumps");
        config.getInlineComments("stats.forced.jumps");
        config.setComments("stats.forced.jumps", List.of("comment"));
        config.setInlineComments("stats.forced.jumps", List.of("comment"));

        assertEquals(Map.of(), usage(config));
    }

    @Test
    void consumedSectionsAreRecorded() throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = load();

        assertNotNull(config.getConfigurationSection("stats.forced"));
        assertNull(config.getConfigurationSection("missing"));

        assertEquals(Map.of(
            "stats.forced", ConfigurationUsage.READ_SECTION,
            "missing", ConfigurationUsage.PROBE
        ), usage(config));
    }

    @Test
    void writesAreRecordedWithoutTheirParentSections() throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = load();

        config.set("settings.version", 2);
        config.set("generated.map", Map.of("a", 1));
        config.createSection("created.section");
        config.set("settings.name", null); // removing a setting is not a use of it

        assertEquals(Map.of(
            "settings.version", ConfigurationUsage.WRITE,
            "generated.map", ConfigurationUsage.WRITE_SECTION,
            "created.section", ConfigurationUsage.WRITE_SECTION
        ), usage(config));
    }

    @Test
    void accessesAccumulate() throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = load();

        config.getString("settings.debug", "default");
        config.getBoolean("settings.debug", false);

        assertEquals(Map.of("settings.debug", ConfigurationUsage.READ_TEXT | ConfigurationUsage.READ_BOOLEAN), usage(config));
    }

    @Test
    void recordingCanBeStopped() throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = load();

        config.stopRecording();
        config.getBoolean("settings.debug", false);

        assertEquals(Map.of(), usage(config));
    }

    @Test
    void recordingContinuesAfterAFailedAccess() throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = load();

        assertThrows(IllegalArgumentException.class, () -> config.set("", 1));
        config.getBoolean("settings.debug", false);

        assertEquals(Map.of("settings.debug", ConfigurationUsage.READ_BOOLEAN), usage(config));
    }

    @Test
    void loadRemembersTheFileEvenIfItDoesNotExist(@TempDir final Path directory) {
        final File file = directory.resolve("missing.yml").toFile();

        final TrackedYamlConfiguration config = TrackedYamlConfiguration.loadConfiguration("missing.yml", file);

        assertEquals(file, config.file());
        assertFalse(config.getKeys(true).iterator().hasNext());
    }
}
