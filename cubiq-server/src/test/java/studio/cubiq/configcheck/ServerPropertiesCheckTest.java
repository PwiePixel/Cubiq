package studio.cubiq.configcheck;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerPropertiesCheckTest {

    private static final String FILE = "server.properties";

    @TempDir
    Path directory;

    @BeforeEach
    @AfterEach
    void reset() {
        ConfigurationCheck.resetForTests();
    }

    private static Properties file(final String contents) throws IOException {
        final Properties properties = new Properties();
        properties.load(new StringReader(contents));
        return properties;
    }

    /**
     * Reads settings like Minecraft does: it records each setting it reads and writes the value it uses back.
     */
    private static PropertiesUsage read(final Properties properties, final String... keys) {
        final PropertiesUsage usage = new PropertiesUsage();
        for (final String key : keys) {
            usage.read(key);
            properties.putIfAbsent(key, "default");
        }
        return usage;
    }

    private static List<ConfigurationFinding> check(final Properties properties, final PropertiesUsage usage) {
        return List.copyOf(ServerPropertiesCheck.findings(FILE, properties, usage).values());
    }

    @Test
    void settingsThatMinecraftReadsAreNotReported() throws IOException {
        final Properties properties = file("max-players=20\nmotd=A Minecraft Server\n");

        assertEquals(List.of(), check(properties, read(properties, "max-players", "motd", "online-mode")));
    }

    @Test
    void anUnknownSettingIsReportedWithTheMostSimilarSetting() throws IOException {
        final Properties properties = file("max-player=50\nonline_mode=false\nspawn-animals=true\n");

        assertEquals(List.of(
            ConfigurationFinding.unknownSetting(FILE, "max-player", "max-players"),
            ConfigurationFinding.unknownSetting(FILE, "online_mode", "online-mode"),
            ConfigurationFinding.unknownSetting(FILE, "spawn-animals", null)
        ), check(properties, read(properties, "max-players", "online-mode", "spawn-protection")));
    }

    @Test
    void replacedSettingsNameWhatReplacesThem() throws IOException {
        final Properties properties = file("pvp=false\nallow-nether=false\n");

        assertEquals(List.of(
            ConfigurationFinding.replacedSetting(FILE, "allow-nether", "misc.enable-nether in paper-global.yml"),
            ConfigurationFinding.replacedSetting(FILE, "pvp", "the game rule pvp")
        ), check(properties, read(properties, "max-players")));
    }

    @Test
    void aReplacedSettingThatMinecraftReadsIsNotReported() throws IOException {
        final Properties properties = file("pvp=false\n");

        assertEquals(List.of(), check(properties, read(properties, "pvp")));
    }

    @Test
    void valuesThatMinecraftDoesNotUseAsWrittenAreReportedWithTheValueItUses() throws IOException {
        final Properties properties = file("white-list=true \nmax-players=lots\n");
        final PropertiesUsage usage = read(properties, "white-list", "max-players");
        // Minecraft writes the values it uses back
        properties.put("white-list", String.valueOf(usage.parsed("white-list", "true ", false, true)));
        usage.parsed("max-players", "lots", null, 20);
        properties.put("max-players", "20");

        assertEquals(List.of(
            ConfigurationFinding.invalidValue(FILE, "max-players", "a whole number", "\"lots\"", "the default value 20"),
            ConfigurationFinding.invalidValue(FILE, "white-list", "true or false", "\"true \" (with whitespace at the end)", "false")
        ), check(properties, usage));
    }

    @Test
    void aValueThatMeansTheDefaultIsNotReported() throws IOException {
        // Minecraft cannot read "20 " and difficulty names are case-sensitive, but the defaults are what they mean
        final Properties properties = file("max-players=20 \ndifficulty=Easy\n");
        final PropertiesUsage usage = read(properties, "max-players", "difficulty");
        usage.parsed("max-players", "20 ", null, 20);
        properties.put("max-players", "20");
        usage.parsed("difficulty", "Easy", null, "easy");
        properties.put("difficulty", "easy");

        assertEquals(List.of(), check(properties, usage));
    }

    @Test
    void settingsWithATypeThatMinecraftWritesAsANumberExpectAWholeNumber() throws IOException {
        final Properties properties = file("op-permission-level=owners\n");
        final PropertiesUsage usage = read(properties, "op-permission-level");
        usage.parsed("op-permission-level", "owners", null, new Object());
        properties.put("op-permission-level", "4");

        assertEquals(List.of(
            ConfigurationFinding.invalidValue(FILE, "op-permission-level", "a whole number", "\"owners\"", "the default value 4")
        ), check(properties, usage));
    }

    @Test
    void legacySettingsAreReportedAlthoughMinecraftRemovedThem() throws IOException {
        // Minecraft removes announce-player-achievements from the properties when it reads it
        final Properties properties = file("max-players=20\nannounce-player-achievements=yes\n");
        final PropertiesUsage usage = read(properties, "max-players", "announce-player-achievements");
        properties.remove("announce-player-achievements");
        usage.parsed("announce-player-achievements", "yes", false, null);

        assertEquals(List.of(
            ConfigurationFinding.invalidValue(FILE, "announce-player-achievements", "true or false", "\"yes\"", "false")
        ), check(properties, usage));
    }

    @Test
    void aByteOrderMarkIsShownAndExplained() throws IOException {
        // Minecraft reads a byte order mark as part of the first key
        final Properties properties = file("\uFEFFmotd=My Server\n");

        assertEquals(List.of(
            ConfigurationFinding.unknownSetting(FILE, "\\ufeffmotd", "motd").withNote(
                "The name starts with an invisible byte order mark, which some editors put at the start of a file. Remove it.")
        ), check(properties, read(properties, "motd")));
    }

    @Test
    void invisibleCharactersAreEscaped() throws IOException {
        // A no-break space, for example pasted from a web page, is not whitespace for Boolean.valueOf either
        final Properties properties = file("white-list=true\u00A0\n");
        final PropertiesUsage usage = read(properties, "white-list");
        properties.put("white-list", String.valueOf(usage.parsed("white-list", "true\u00A0", false, true)));

        assertEquals(List.of(
            ConfigurationFinding.invalidValue(FILE, "white-list", "true or false", "\"true\\u00a0\" (with whitespace at the end)", "false")
        ), check(properties, usage));
    }

    @Test
    void controlCharactersAreEscaped() throws IOException {
        // Escape sequences in the file can put control characters into keys and values
        final Properties properties = file("fake\\nline=1\nmotd=a\\tb\n");
        final PropertiesUsage usage = read(properties, "motd");
        usage.parsed("motd", "a\tb", null, "a\nb");
        properties.put("motd", "a\nb");

        assertEquals(List.of(
            ConfigurationFinding.unknownSetting(FILE, "fake\\nline", null),
            ConfigurationFinding.invalidValue(FILE, "motd", "a valid value", "\"a\\tb\"", "the default value a\\nb")
        ), check(properties, usage));
    }

    @Test
    void loadedFilesAreReportedWhileTheCheckIsPending() throws IOException {
        final Path file = this.directory.resolve(FILE);
        Files.writeString(file, "max-player=50\npvp=false\n");
        final Properties properties = file(Files.readString(file));

        ServerPropertiesCheck.loaded(file, properties, read(properties, "max-players"));

        assertEquals(List.of(new CheckedFile(CheckedFile.displayName(file), List.of(
            ConfigurationFinding.unknownSetting(CheckedFile.displayName(file), "max-player", "max-players"),
            ConfigurationFinding.replacedSetting(CheckedFile.displayName(file), "pvp", "the game rule pvp")
        ))), ServerPropertiesCheck.drain());
        assertEquals(List.of(), ServerPropertiesCheck.drain(), "drained");
    }

    @Test
    void aFileThatIsLoadedAgainBeforeTheCheckIsReportedOnceWithTheFindingsOfTheFirstLoad() throws IOException {
        final Path file = this.directory.resolve(FILE);
        Files.writeString(file, "max-player=50\nwhite-list=false\n");
        final Properties first = file("max-player=50\nwhite-list=true \n");
        final PropertiesUsage firstUsage = read(first, "white-list");
        firstUsage.parsed("white-list", "true ", false, true);
        first.put("white-list", "false");
        ServerPropertiesCheck.loaded(file, first, firstUsage);
        // A /reload reads the file that Minecraft wrote with the values it uses
        final Properties second = file(Files.readString(file));
        ServerPropertiesCheck.loaded(file, second, read(second, "white-list"));

        assertEquals(List.of(new CheckedFile(CheckedFile.displayName(file), List.of(
            ConfigurationFinding.unknownSetting(CheckedFile.displayName(file), "max-player", null),
            ConfigurationFinding.invalidValue(CheckedFile.displayName(file), "white-list", "true or false", "\"true \" (with whitespace at the end)", "false")
        ))), ServerPropertiesCheck.drain());
    }

    @Test
    void longKeysAreShortened() throws IOException {
        // A line that is not a setting, for example a pasted token, becomes a key of any length
        final String token = "x".repeat(500);
        final Path file = this.directory.resolve(FILE);
        Files.writeString(file, token + "\n");
        final Properties properties = file(Files.readString(file));
        ServerPropertiesCheck.loaded(file, properties, read(properties, "max-players"));

        assertEquals(List.of(new CheckedFile(CheckedFile.displayName(file), List.of(
            ConfigurationFinding.unknownSetting(CheckedFile.displayName(file), "x".repeat(77) + "...", null)
        ))), ServerPropertiesCheck.drain());
    }

    @Test
    void settingsThatTheServerRemovedWhileStartingAreReportedWithANote() throws IOException {
        final Path file = this.directory.resolve(FILE);
        Files.writeString(file, "max-player=50\npvp=false\nallow-nether=false\nview-distance=10\n");
        final Properties properties = file(Files.readString(file));
        ServerPropertiesCheck.loaded(file, properties, read(properties, "max-players", "view-distance"));
        // When Paper updates paper-global.yml from an older version, it moves the value of allow-nether to its own
        // configuration and writes server.properties with only the settings Minecraft writes back
        Files.writeString(file, "max-players=20\nview-distance=10\n");
        final String name = CheckedFile.displayName(file);

        assertEquals(List.of(new CheckedFile(name, List.of(
            ConfigurationFinding.unknownSetting(name, "max-player", "max-players").withNote("The server removed it from the file while starting."),
            ConfigurationFinding.replacedSetting(name, "pvp", "the game rule pvp").withNote("The server removed it from the file while starting.")
        ))), ServerPropertiesCheck.drain());
    }

    @Test
    void everythingIsReportedIfTheFileCannotBeReadAgain() throws IOException {
        final Path file = this.directory.resolve(FILE);
        final Properties properties = file("max-player=50\n");
        ServerPropertiesCheck.loaded(file, properties, read(properties, "max-players"));
        // A directory exists but cannot be read as a file
        Files.createDirectory(file);

        assertEquals(List.of(new CheckedFile(CheckedFile.displayName(file), List.of(
            ConfigurationFinding.unknownSetting(CheckedFile.displayName(file), "max-player", "max-players")
        ))), ServerPropertiesCheck.drain());
    }

    @Test
    void filesLoadedAfterTheCheckAreNotChecked() throws IOException {
        // For example when /reload loads server.properties again
        ConfigurationCheck.runAfterStartup();
        final Properties properties = file("max-player=50\n");

        ServerPropertiesCheck.loaded(this.directory.resolve(FILE), properties, read(properties, "max-players"));

        assertEquals(List.of(), ServerPropertiesCheck.drain());
    }

    @Test
    void anErrorInTheCheckDoesNotAffectLoading() throws IOException {
        ServerPropertiesCheck.loaded(this.directory.resolve(FILE), file("max-player=50\n"), null);

        assertEquals(List.of(), ServerPropertiesCheck.drain());
    }

    @Test
    void theFileIsReadAgainLikeMinecraftReadsIt() throws IOException {
        final Path utf8 = this.directory.resolve("utf8.properties");
        Files.writeString(utf8, "motd=Grüße\nmax-players=20\n", StandardCharsets.UTF_8);
        final Path latin1 = this.directory.resolve("latin1.properties");
        // Not valid UTF-8, so Minecraft falls back to ISO 8859-1
        Files.writeString(latin1, "motd=Grüße\nmax-players=20\n", StandardCharsets.ISO_8859_1);

        assertEquals(Set.of("motd", "max-players"), ServerPropertiesCheck.keysInFile(utf8));
        assertEquals(Set.of("motd", "max-players"), ServerPropertiesCheck.keysInFile(latin1));
        assertEquals(Set.of(), ServerPropertiesCheck.keysInFile(this.directory.resolve("missing.properties")));
    }
}
