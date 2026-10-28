package studio.cubiq.configcheck;

import io.papermc.paper.configuration.PaperConfigurations;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import joptsimple.OptionParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the check of {@code server.properties} against Minecraft's real code for the file: the file that Minecraft
 * generates must have no findings, and mistakes in it must be found.
 */
@Normal
class ServerPropertiesFilesTest {

    private static final String GAME_RULE = "the game rule ";
    private static final Pattern PAPER_GLOBAL_SETTING = Pattern.compile("(\\S+) in paper-global\\.yml");
    private static final String REMOVED = "The server removed it from the file while starting.";

    @TempDir
    Path directory;

    @BeforeEach
    @AfterEach
    void reset() {
        ConfigurationCheck.resetForTests();
    }

    private Path file() {
        return this.directory.resolve("server.properties");
    }

    private String name() {
        return CheckedFile.displayName(this.file());
    }

    /**
     * Loads {@code server.properties} like the server does when it starts: Minecraft's {@code Main} loads the file
     * and saves it right away, with the values it uses.
     *
     * @return the findings
     */
    private List<ConfigurationFinding> start() {
        return this.start(false);
    }

    /**
     * Like {@link #start()}.
     *
     * @param paperUpdatesItsConfiguration whether to write the file again like Paper did before Cubiq's fix, when it
     *                                     updated {@code paper-global.yml} from an older version
     */
    private List<ConfigurationFinding> start(final boolean paperUpdatesItsConfiguration) {
        ConfigurationCheck.resetForTests();
        final OptionParser parser = new OptionParser();
        parser.accepts("config").withRequiredArg().ofType(File.class);
        final DedicatedServerSettings settings = new DedicatedServerSettings(parser.parse("--config", this.file().toString()));
        settings.forceSave();
        if (paperUpdatesItsConfiguration) {
            // What V31_AllowNetherPropertiesToConfig did: the loaded settings become the defaults of the new ones,
            // and Properties.store only writes the settings that DedicatedServerProperties writes back
            settings.update(properties -> properties.reload(RegistryHelper.registryAccess(), new Properties(properties.properties), null));
        }
        final List<CheckedFile> results = ServerPropertiesCheck.drain();
        assertEquals(1, results.size());
        assertEquals(this.name(), results.getFirst().file());
        return results.getFirst().findings();
    }

    private void edit(final Map<String, String> settings) throws IOException {
        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(this.file(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        properties.putAll(settings);
        try (Writer writer = Files.newBufferedWriter(this.file(), StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }
    }

    @Test
    void theGeneratedFileHasNoFindings() {
        assertEquals(List.of(), this.start(), "first start");
        // Minecraft has now written every setting it reads to the file
        assertEquals(List.of(), this.start(), "second start");
    }

    @Test
    void mistakesAreFound() throws IOException {
        this.start();
        this.edit(Map.of(
            "max-player", "50",
            "spawn-animals", "true",
            "pvp", "false",
            "white-list", "true ",
            "view-distance", "twelve",
            "gamemode", "Creative",
            "difficulty", "Hard",
            "online-mode", "yes",
            "server-port", "abc"
        ));
        final String name = this.name();

        assertEquals(List.of(
            ConfigurationFinding.invalidValue(name, "difficulty", "one of peaceful, easy, normal, hard", "\"Hard\"", "the default value easy"),
            ConfigurationFinding.invalidValue(name, "gamemode", "one of survival, creative, adventure, spectator", "\"Creative\"", "survival"),
            ConfigurationFinding.unknownSetting(name, "max-player", "max-players"),
            ConfigurationFinding.invalidValue(name, "online-mode", "true or false", "\"yes\"", "false"),
            ConfigurationFinding.replacedSetting(name, "pvp", "the game rule pvp"),
            ConfigurationFinding.invalidValue(name, "server-port", "a whole number", "\"abc\"", "the default value 25565"),
            ConfigurationFinding.unknownSetting(name, "spawn-animals", null),
            ConfigurationFinding.invalidValue(name, "view-distance", "a whole number", "\"twelve\"", "the default value 10"),
            ConfigurationFinding.invalidValue(name, "white-list", "true or false", "\"true \" (with whitespace at the end)", "false")
        ), this.start());
        // Minecraft replaced the values it could not use in the file, so only the settings without effect remain
        assertEquals(List.of(
            ConfigurationFinding.unknownSetting(name, "max-player", "max-players"),
            ConfigurationFinding.replacedSetting(name, "pvp", "the game rule pvp"),
            ConfigurationFinding.unknownSetting(name, "spawn-animals", null)
        ), this.start());
    }

    @Test
    void minecraftReadsTheSameValuesAsWithoutTheCheck() throws IOException {
        this.start();
        // Settings read through get(), getMutable() and getLegacy(), with valid and invalid values
        this.edit(Map.of(
            "online-mode", "yes",
            "hardcore", "true",
            "server-port", "abc",
            "query.port", "25566",
            "max-players", "lots",
            "view-distance", "12",
            "gamemode", "Creative",
            "difficulty", "hard",
            "announce-player-achievements", "TRUE"
        ));

        final DedicatedServerProperties properties = DedicatedServerProperties.fromFile(this.file(), null);

        assertFalse(properties.onlineMode, "Boolean.valueOf(\"yes\")");
        assertTrue(properties.hardcore);
        assertEquals(25565, properties.serverPort, "the default for a value that is no number");
        assertEquals(25566, properties.queryPort);
        assertEquals(20, properties.maxPlayers.get(), "the default for a value that is no number");
        assertEquals(12, properties.viewDistance.get());
        assertEquals(GameType.SURVIVAL, properties.gameMode.get(), "GameType.byName falls back to survival");
        assertEquals(Difficulty.HARD, properties.difficulty.get());
        assertEquals(Boolean.TRUE, properties.announcePlayerAchievements);
    }

    @Test
    void valuesThatMeanWhatTheServerUsesAreNotReported() throws IOException {
        this.start();
        // Difficulty names are case-sensitive and "20 " is no number, but the defaults are what they mean
        this.edit(Map.of("difficulty", "Easy", "max-players", "20 ", "white-list", "false "));

        assertEquals(List.of(), this.start());
    }

    @Test
    void legacySettingsAreReported() throws IOException {
        this.start();
        // Minecraft moves it to the game rule show_advancement_messages of every world and removes it from the file
        this.edit(Map.of("announce-player-achievements", "yes"));

        assertEquals(List.of(
            ConfigurationFinding.invalidValue(this.name(), "announce-player-achievements", "true or false", "\"yes\"", "false").withNote(REMOVED)
        ), this.start());
    }

    @Test
    void settingsThatAreRemovedWhileTheServerStartsAreReported() throws IOException {
        this.start();
        this.edit(Map.of("max-player", "50", "pvp", "false", "allow-nether", "false"));
        final String name = this.name();

        // Paper moves the value of allow-nether to paper-global.yml; the other values would be lost
        assertEquals(List.of(
            ConfigurationFinding.unknownSetting(name, "max-player", "max-players").withNote(REMOVED),
            ConfigurationFinding.replacedSetting(name, "pvp", "the game rule pvp").withNote(REMOVED)
        ), this.start(true));
        assertEquals(List.of(), this.start(), "the settings are no longer in the file");
    }

    @Test
    void aByteOrderMarkIsReported() throws IOException {
        this.start();
        Files.writeString(this.file(), "\uFEFF" + Files.readString(this.file(), StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        // The first line of the file that Minecraft writes is a comment, which becomes a setting with the mark
        assertEquals(List.of(
            ConfigurationFinding.unknownSetting(this.name(), "\\ufeff#Minecraft", null).withNote(
                "The name starts with an invisible byte order mark, which some editors put at the start of a file. Remove it.")
        ), this.start());
    }

    @Test
    void replacedSettingsAreNotReadAndWhatReplacesThemExists() throws IOException {
        final DedicatedServerProperties properties = new DedicatedServerProperties(new Properties(), null);
        for (final Map.Entry<String, String> replaced : ServerPropertiesCheck.REPLACED.entrySet()) {
            assertFalse(properties.usage.readKeys().contains(replaced.getKey()), replaced.getKey() + " is read from server.properties");
            final String replacement = replaced.getValue();
            if (replacement.startsWith(GAME_RULE)) {
                final String gameRule = replacement.substring(GAME_RULE.length());
                assertTrue(BuiltInRegistries.GAME_RULE.containsKey(Identifier.withDefaultNamespace(gameRule)), "unknown game rule " + gameRule);
                continue;
            }
            final Matcher setting = PAPER_GLOBAL_SETTING.matcher(replacement);
            assertTrue(setting.matches(), "unknown kind of replacement: " + replacement);
            new PaperConfigurations(this.directory.resolve("config")).initializeGlobalConfiguration(RegistryHelper.registryAccess());
            final YamlConfiguration global = YamlConfiguration.loadConfiguration(this.directory.resolve("config").resolve("paper-global.yml").toFile());
            assertTrue(global.contains(setting.group(1)), "Paper has no setting " + setting.group(1));
        }
    }
}
