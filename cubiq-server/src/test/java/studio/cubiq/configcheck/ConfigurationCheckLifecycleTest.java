package studio.cubiq.configcheck;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationCheckLifecycleTest {

    @BeforeEach
    @AfterEach
    void reset() {
        ConfigurationCheck.resetForTests();
    }

    private static TrackedYamlConfiguration load(final Path directory, final String name, final String contents) throws IOException {
        final File file = directory.resolve(name).toFile();
        Files.writeString(file.toPath(), contents);
        return TrackedYamlConfiguration.loadConfiguration(name, file);
    }

    @Test
    void recordingStopsAndRecordedUsageIsReleasedWhenTheCheckRuns(@TempDir final Path directory) throws IOException {
        final TrackedYamlConfiguration config = load(directory, "spigot.yml", "settings:\n  debug: true\n");
        config.getBoolean("settings.debug", false);
        assertEquals(Map.of("settings.debug", ConfigurationUsage.READ_BOOLEAN), config.usage().snapshot());

        ConfigurationCheck.runAfterStartup();
        config.getBoolean("settings.debug", false);

        assertFalse(ConfigurationCheck.isPending());
        assertEquals(Map.of(), config.usage().snapshot());
    }

    @Test
    void configurationsCreatedAfterTheCheckDoNotRecord(@TempDir final Path directory) throws IOException {
        ConfigurationCheck.runAfterStartup();

        // Like /reload, which creates new configuration objects
        final TrackedYamlConfiguration reloaded = load(directory, "bukkit.yml", "settings:\n  allow-end: true\n");
        reloaded.getBoolean("settings.allow-end", true);

        assertEquals(Map.of(), reloaded.usage().snapshot());
    }

    @Test
    void theCheckRunsOnlyOnce(@TempDir final Path directory) throws IOException {
        ConfigurationCheck.runAfterStartup();
        final TrackedYamlConfiguration config = load(directory, "purpur.yml", "settings:\n  verbose: true\n");

        ConfigurationCheck.runAfterStartup();

        assertFalse(ConfigurationCheck.isPending());
        assertEquals(Map.of(), config.usage().snapshot());
    }

    @Test
    void unreadableFilesAreNotReportedAsChecked(@TempDir final Path directory) throws IOException {
        final TrackedYamlConfiguration config = load(directory, "spigot.yml", "settings:\n  debug: true\n");
        final File file = directory.resolve("spigot.yml").toFile();
        Files.writeString(file.toPath(), "settings: [unclosed\n");

        assertNull(ConfigurationCheck.check(config, file));
    }

    @Test
    void readableFilesAreChecked(@TempDir final Path directory) throws IOException {
        final TrackedYamlConfiguration config = load(directory, "spigot.yml", "settings:\n  debug: true\n");
        config.getBoolean("settings.debug", false);

        assertTrue(ConfigurationCheck.check(config, directory.resolve("spigot.yml").toFile()).isEmpty());
    }
}
