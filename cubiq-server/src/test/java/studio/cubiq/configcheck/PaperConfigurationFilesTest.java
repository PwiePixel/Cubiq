package studio.cubiq.configcheck;

import io.papermc.paper.configuration.PaperConfigurations;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.gamerules.GameRules;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spigotmc.SpigotConfig;
import org.spigotmc.SpigotWorldConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the check of Paper's configuration files against Paper's real configuration code: the files that Paper
 * generates must have no findings, and mistakes in them must be found.
 */
@Normal
class PaperConfigurationFilesTest {

    @TempDir
    Path directory;

    private File globalFile() {
        return this.directory.resolve("config").resolve("paper-global.yml").toFile();
    }

    private File worldDefaultsFile() {
        return this.directory.resolve("config").resolve("paper-world-defaults.yml").toFile();
    }

    private File worldFile() {
        return this.directory.resolve("world").resolve("paper-world.yml").toFile();
    }

    private static String name(final File file) {
        return CheckedFile.displayName(file.toPath());
    }

    private static void edit(final File file, final String path, final Object value) throws IOException, InvalidConfigurationException {
        final YamlConfiguration contents = new YamlConfiguration();
        contents.load(file);
        contents.set(path, value);
        contents.save(file);
    }

    @BeforeEach
    void setUp() {
        ConfigurationCheck.resetForTests();
        // Paper's world configurations read Spigot's world settings
        SpigotConfig.init(this.directory.resolve("spigot.yml").toFile());
    }

    /**
     * Loads Paper's configuration files like the server does when it starts, and returns the results of the check.
     */
    private List<CheckedFile> start() throws IOException {
        ConfigurationCheck.resetForTests();
        final PaperConfigurations configurations = new PaperConfigurations(this.directory.resolve("config"));
        configurations.initializeGlobalConfiguration(RegistryHelper.registryAccess());
        configurations.initializeWorldDefaultsConfiguration(RegistryHelper.registryAccess());
        configurations.createWorldConfig(PaperConfigurations.createWorldContextMap(
            this.directory.resolve("world"),
            Identifier.withDefaultNamespace("overworld"),
            new SpigotWorldConfig("world", Key.key("minecraft", "overworld")),
            RegistryHelper.registryAccess(),
            new GameRules(FeatureFlags.REGISTRY.allFlags())
        ));
        return PaperConfigurationCheck.drain();
    }

    private static List<CheckedFile> clean(final File... files) {
        return Arrays.stream(files).map(file -> new CheckedFile(name(file), List.of())).toList();
    }

    @Test
    void generatedFilesHaveNoFindings() throws IOException {
        assertEquals(clean(this.globalFile(), this.worldDefaultsFile(), this.worldFile()), this.start(), "first start");
        // Paper has now written every setting it knows to the files
        assertEquals(clean(this.globalFile(), this.worldDefaultsFile(), this.worldFile()), this.start(), "second start");
    }

    @Test
    void mistakesInEveryFileAreFound() throws IOException, InvalidConfigurationException {
        this.start();
        edit(this.globalFile(), "chunk-loading-basic.player-max-chunk-load-rat", 50.0);
        edit(this.worldDefaultsFile(), "entities.spawning.spawn-limits.monsters", 10);
        edit(this.worldFile(), "chunks.auto-save-intervall", 6000);

        final List<CheckedFile> results = this.start();

        assertEquals(List.of(
            new CheckedFile(name(this.globalFile()), List.of(ConfigurationFinding.unknownSetting(
                name(this.globalFile()), "chunk-loading-basic.player-max-chunk-load-rat", "chunk-loading-basic.player-max-chunk-load-rate"
            ))),
            new CheckedFile(name(this.worldDefaultsFile()), List.of(ConfigurationFinding.unknownSetting(
                name(this.worldDefaultsFile()), "entities.spawning.spawn-limits.monsters", "entities.spawning.spawn-limits.monster"
            ))),
            new CheckedFile(name(this.worldFile()), List.of(ConfigurationFinding.unknownSetting(
                name(this.worldFile()), "chunks.auto-save-intervall", "chunks.auto-save-interval"
            )))
        ), results);
    }

    @Test
    void registryKeysWithTheMinecraftNamespaceAreValid() throws IOException, InvalidConfigurationException {
        this.start();
        edit(this.worldDefaultsFile(), "chunks.entity-per-chunk-save-limit.arrow", null);
        edit(this.worldDefaultsFile(), "chunks.entity-per-chunk-save-limit.minecraft:arrow", 16);

        assertEquals(clean(this.globalFile(), this.worldDefaultsFile(), this.worldFile()), this.start());
    }
}
