package studio.cubiq.configcheck;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.key.Key;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.purpurmc.purpur.PurpurConfig;
import org.purpurmc.purpur.PurpurWorldConfig;
import org.spigotmc.SpigotConfig;
import org.spigotmc.SpigotWorldConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the configuration check against the real configuration code of CraftBukkit, Spigot and Purpur.
 *
 * <p>The important property is that freshly generated files produce no findings, both on the first start and on
 * later starts, when the files already contain every setting. Purpur only registers defaults for world settings
 * that are missing from the file, so a check based on defaults alone would report every world setting on the
 * second start.</p>
 */
@Normal
class UpstreamConfigurationsTest {

    private static final Key OVERWORLD = Key.key("minecraft", "overworld");
    private static final Key NETHER = Key.key("minecraft", "the_nether");

    private static List<ConfigurationFinding> check(final TrackedYamlConfiguration configuration) {
        final File file = Objects.requireNonNull(configuration.file());
        return Objects.requireNonNull(ConfigurationCheck.check(configuration, file), "the file could not be read");
    }

    private static void edit(final File file, final String path, final Object value) throws IOException, InvalidConfigurationException {
        final YamlConfiguration contents = new YamlConfiguration();
        contents.load(file);
        contents.set(path, value);
        contents.save(file);
    }

    // Spigot

    private static TrackedYamlConfiguration startSpigot(final File file) {
        SpigotConfig.init(file);
        new SpigotWorldConfig("world", OVERWORLD);
        new SpigotWorldConfig("world_nether", NETHER);
        return (TrackedYamlConfiguration) SpigotConfig.config;
    }

    @Test
    void generatedSpigotConfigurationHasNoFindings(@TempDir final Path directory) {
        final File file = directory.resolve("spigot.yml").toFile();

        assertEquals(List.of(), check(startSpigot(file)), "first start");
        assertEquals(List.of(), check(startSpigot(file)), "second start");
    }

    @Test
    void spigotTyposAreReported(@TempDir final Path directory) throws IOException, InvalidConfigurationException {
        final File file = directory.resolve("spigot.yml").toFile();
        startSpigot(file);
        edit(file, "settings.timout-time", 120);
        edit(file, "world-settings.minecraft:the_nether.mob-spawn-rang", 4);
        edit(file, "world-settings.default.mob-spawn-range", "eight");

        final List<ConfigurationFinding> findings = check(startSpigot(file));

        assertEquals(List.of(
            ConfigurationFinding.unknownSetting(file.getPath(), "settings.timout-time", "settings.timeout-time"),
            ConfigurationFinding.invalidValue(file.getPath(), "world-settings.default.mob-spawn-range", "a number", "\"eight\""),
            ConfigurationFinding.unknownSetting(file.getPath(), "world-settings.minecraft:the_nether.mob-spawn-rang", "world-settings.minecraft:the_nether.mob-spawn-range")
        ), findings);
    }

    @Test
    void spigotWorldOverridesThatUseTheServerPropertiesAreValid(@TempDir final Path directory) throws IOException, InvalidConfigurationException {
        final File file = directory.resolve("spigot.yml").toFile();
        startSpigot(file);
        // "default" means: use the distance from server.properties. Spigot reads these settings with get(path, def),
        // passing the value of the default scope (a number here) as the fallback.
        edit(file, "world-settings.default.view-distance", 10);
        edit(file, "world-settings.minecraft:the_nether.view-distance", "default");
        edit(file, "world-settings.minecraft:the_nether.simulation-distance", "default");

        assertEquals(List.of(), check(startSpigot(file)));
    }

    // Purpur world settings

    private static TrackedYamlConfiguration startPurpurWorlds(final File file) throws ReflectiveOperationException {
        // PurpurConfig#init needs a running server, so set up its configuration the same way without the global settings
        final TrackedYamlConfiguration configuration = TrackedYamlConfiguration.loadConfiguration("purpur.yml", file);
        configuration.options().copyDefaults(true);
        PurpurConfig.config = configuration;
        PurpurConfig.version = 50;
        final Field configFile = PurpurConfig.class.getDeclaredField("CONFIG_FILE");
        configFile.setAccessible(true);
        configFile.set(null, file);

        new PurpurWorldConfig("world", World.Environment.NORMAL, OVERWORLD);
        new PurpurWorldConfig("world_nether", World.Environment.NETHER, NETHER);
        return configuration;
    }

    @Test
    void generatedPurpurWorldSettingsHaveNoFindings(@TempDir final Path directory) throws ReflectiveOperationException {
        final File file = directory.resolve("purpur.yml").toFile();

        assertEquals(List.of(), check(startPurpurWorlds(file)), "first start");
        assertEquals(List.of(), check(startPurpurWorlds(file)), "second start");
        assertEquals(List.of(), check(startPurpurWorlds(file)), "third start");
    }

    @Test
    void purpurWorldSettingTyposAreReported(@TempDir final Path directory) throws ReflectiveOperationException, IOException, InvalidConfigurationException {
        final File file = directory.resolve("purpur.yml").toFile();
        startPurpurWorlds(file);
        edit(file, "world-settings.default.mobs.zombie.ridabel", true);

        final List<ConfigurationFinding> findings = check(startPurpurWorlds(file));

        assertEquals(List.of(
            ConfigurationFinding.unknownSetting(file.getPath(), "world-settings.default.mobs.zombie.ridabel", "world-settings.default.mobs.zombie.ridable")
        ), findings);
    }

    // CraftBukkit

    private static TrackedYamlConfiguration startBukkit(final File file) throws IOException {
        // Mirrors how CraftServer loads bukkit.yml
        final TrackedYamlConfiguration configuration = TrackedYamlConfiguration.loadConfiguration("bukkit.yml", file);
        configuration.options().copyDefaults(true);
        try (InputStream defaults = Objects.requireNonNull(UpstreamConfigurationsTest.class.getClassLoader().getResourceAsStream("configurations/bukkit.yml"))) {
            configuration.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        configuration.save(file);
        return configuration;
    }

    @Test
    void generatedBukkitConfigurationHasNoFindings(@TempDir final Path directory) throws IOException {
        final File file = directory.resolve("bukkit.yml").toFile();

        assertEquals(List.of(), check(startBukkit(file)), "first start");
        assertEquals(List.of(), check(startBukkit(file)), "second start");
    }

    @Test
    void bukkitWorldContainerIsUsed(@TempDir final Path directory) throws IOException, InvalidConfigurationException {
        final File file = directory.resolve("bukkit.yml").toFile();
        startBukkit(file);
        // Read by net.minecraft.server.Main from its own copy of bukkit.yml, never by CraftServer
        edit(file, "settings.world-container", "worlds");

        assertEquals(List.of(), check(startBukkit(file)));
    }

    @Test
    void bukkitWorldGeneratorSettingsAreChecked(@TempDir final Path directory) throws IOException, InvalidConfigurationException {
        final File file = directory.resolve("bukkit.yml").toFile();
        startBukkit(file);
        edit(file, "worlds.world.generator", "Terra:OVERWORLD");
        edit(file, "worlds.world_nether.generater", "Terra:NETHER");
        final TrackedYamlConfiguration configuration = startBukkit(file);
        // As CraftServer#getGenerator does when the worlds load
        configuration.getConfigurationSection("worlds");

        assertEquals(
            List.of(ConfigurationFinding.unknownSetting(file.getPath(), "worlds.world_nether.generater", "worlds.world_nether.generator")),
            check(configuration)
        );
    }

    @Test
    void purpurSettingsReadOnlyConditionallyAreUsed(@TempDir final Path directory) throws IOException {
        final File file = directory.resolve("purpur.yml").toFile();
        final YamlConfiguration contents = new YamlConfiguration();
        contents.set("world-settings.minecraft:overworld.gameplay-mechanics.player.idle-timeout.kick-if-idle", true);
        contents.save(file);
        // As if PURPUR_FORCE_IDLE_KICK were set: Purpur does not read the setting at all
        final TrackedYamlConfiguration configuration = TrackedYamlConfiguration.loadConfiguration("purpur.yml", file);

        assertEquals(List.of(), check(configuration));
    }

    @Test
    void bukkitTyposAreReported(@TempDir final Path directory) throws IOException, InvalidConfigurationException {
        final File file = directory.resolve("bukkit.yml").toFile();
        startBukkit(file);
        edit(file, "spawn-limits.monster", 50);
        // Only read when a player joins, so the type comes from the default value
        edit(file, "settings.connection-throttle", "lots");

        assertEquals(
            List.of(
                ConfigurationFinding.invalidValue(file.getPath(), "settings.connection-throttle", "a number", "\"lots\""),
                ConfigurationFinding.unknownSetting(file.getPath(), "spawn-limits.monster", "spawn-limits.monsters")
            ),
            check(startBukkit(file))
        );
    }
}
