package studio.cubiq.configcheck;

import java.util.List;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigurationCheckerTest {

    private static final ConfigurationChecker.Scope WORLDS = new ConfigurationChecker.Scope("world-settings", "default");

    /**
     * Loads {@code yaml} as the server would, lets {@code server} access it, and checks the same text as it would
     * be read back from disk.
     */
    private static List<ConfigurationFinding> check(
        final String yaml,
        final @Nullable YamlConfiguration defaults,
        final ConfigurationChecker.@Nullable Scope scope,
        final ServerAccess server
    ) throws InvalidConfigurationException {
        final TrackedYamlConfiguration config = new TrackedYamlConfiguration("test.yml");
        config.loadFromString(yaml);
        if (defaults != null) {
            config.setDefaults(defaults);
        }
        server.access(config);

        final YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.loadFromString(yaml);
        return new ConfigurationChecker("test.yml", config.usage().snapshot(), config.getDefaults(), scope).check(onDisk);
    }

    private static List<ConfigurationFinding> check(final String yaml, final ServerAccess server) throws InvalidConfigurationException {
        return check(yaml, null, null, server);
    }

    @FunctionalInterface
    private interface ServerAccess {
        void access(TrackedYamlConfiguration config);
    }

    private static final ServerAccess READ_SETTINGS = config -> {
        config.getBoolean("settings.debug", false);
        config.getInt("settings.timeout", 60);
        config.getString("messages.whitelist", "You are not whitelisted");
        config.getString("messages.restart", "Server is restarting");
    };

    @Test
    void usedSettingsAreNotReported() throws InvalidConfigurationException {
        final String yaml = """
            settings:
              debug: false
              timeout: 30
            messages:
              whitelist: Not whitelisted
            """;

        assertEquals(List.of(), check(yaml, READ_SETTINGS));
    }

    @Test
    void misspelledSettingIsReportedWithSuggestion() throws InvalidConfigurationException {
        final String yaml = """
            settings:
              debug: false
              timout: 30
            """;

        assertEquals(
            List.of(ConfigurationFinding.unknownSetting("test.yml", "settings.timout", "settings.timeout")),
            check(yaml, READ_SETTINGS)
        );
    }

    @Test
    void unknownSectionIsReportedOnce() throws InvalidConfigurationException {
        final String yaml = """
            messges:
              whitelist: Not whitelisted
              restart: Restarting
            """;

        assertEquals(
            List.of(ConfigurationFinding.unknownSection("test.yml", "messges", 2, "messages")),
            check(yaml, READ_SETTINGS)
        );
    }

    @Test
    void unknownSettingWithoutSimilarSettingHasNoSuggestion() throws InvalidConfigurationException {
        final String yaml = """
            settings:
              completely-unrelated: 1
            """;

        assertEquals(
            List.of(ConfigurationFinding.unknownSetting("test.yml", "settings.completely-unrelated", null)),
            check(yaml, READ_SETTINGS)
        );
    }

    @Test
    void settingInTheWrongSectionSuggestsWhereItBelongs() throws InvalidConfigurationException {
        final String yaml = """
            timeout: 30
            """;

        assertEquals(
            List.of(ConfigurationFinding.unknownSetting("test.yml", "timeout", "settings.timeout")),
            check(yaml, READ_SETTINGS)
        );
    }

    @Test
    void separatorStyleAndCaseDifferencesAreSuggested() throws InvalidConfigurationException {
        final String yaml = """
            settings:
              Time_Out: 30
            """;
        final ServerAccess server = config -> config.getInt("settings.time-out", 60);

        assertEquals(
            List.of(ConfigurationFinding.unknownSetting("test.yml", "settings.Time_Out", "settings.time-out")),
            check(yaml, server)
        );
    }

    @Test
    void worldScopesAreCheckedAgainstTheDefaultScope() throws InvalidConfigurationException {
        final String yaml = """
            world-settings:
              default:
                mob-spawn-range: 8
              world_nether:
                mob-spawn-range: 6
                mob-spawn-rang: 4
            """;
        // Only the overworld is loaded; the nether settings are still validated against the defaults
        final ServerAccess server = config -> {
            config.addDefault("world-settings.default.mob-spawn-range", 8);
            config.getInt("world-settings.minecraft:overworld.mob-spawn-range", config.getInt("world-settings.default.mob-spawn-range"));
        };

        assertEquals(
            List.of(ConfigurationFinding.unknownSetting("test.yml", "world-settings.world_nether.mob-spawn-rang", "world-settings.world_nether.mob-spawn-range")),
            check(yaml, null, WORLDS, server)
        );
    }

    @Test
    void worldKeysContainingDotsAreSupported() throws InvalidConfigurationException {
        // Worlds created by plugins can have keys like minecraft:event.2026, which YAML paths split into sections
        final String yaml = """
            world-settings:
              minecraft:event.2026:
                mob-spawn-range: 4
                mobs:
                  zombie: true
            """;
        final ServerAccess server = config -> {
            config.getInt("world-settings.default.mob-spawn-range", 8);
            config.getConfigurationSection("world-settings.minecraft:overworld.mobs");
            config.set("world-settings.minecraft:overworld.mobs.zombie", false);
        };

        assertEquals(List.of(), check(yaml, null, WORLDS, server));
    }

    @Test
    void scopesAreOnlyAppliedWhenConfigured() throws InvalidConfigurationException {
        final String yaml = """
            world-settings:
              world_nether:
                mob-spawn-range: 6
            """;
        final ServerAccess server = config -> config.getInt("world-settings.default.mob-spawn-range", 8);

        assertEquals(
            List.of(ConfigurationFinding.unknownSection("test.yml", "world-settings.world_nether", 1, null)),
            check(yaml, null, null, server)
        );
    }

    @Test
    void consumedSectionsAcceptAnyChild() throws InvalidConfigurationException {
        final String yaml = """
            stats:
              forced-stats:
                minecraft:jump: 5
                anything: 1
            """;
        final ServerAccess server = config -> config.getConfigurationSection("stats.forced-stats");

        assertEquals(List.of(), check(yaml, server));
    }

    @Test
    void consumedSectionInOneWorldAcceptsAnyChildInEveryWorld() throws InvalidConfigurationException {
        final String yaml = """
            world-settings:
              world_nether:
                mobs:
                  zombie: true
              world_the_end:
                mobs:
                  enderman:
                    anything: true
            """;
        // Only the nether is loaded, and it reads its map of mobs as a whole
        final ServerAccess server = config -> config.getConfigurationSection("world-settings.world_nether.mobs");

        assertEquals(List.of(), check(yaml, null, WORLDS, server));
    }

    @Test
    void missingSectionThatWasLookedUpIsNotFreeForm() throws InvalidConfigurationException {
        final String yaml = """
            world-settings:
              world_nether:
                mobs:
                  zombie: true
            """;
        // The section does not exist in the scope the server read, so its children were never processed
        final ServerAccess server = config -> config.getConfigurationSection("world-settings.default.mobs");

        assertEquals(
            List.of(ConfigurationFinding.unknownSetting("test.yml", "world-settings.world_nether.mobs.zombie", null)),
            check(yaml, null, WORLDS, server)
        );
    }

    @Test
    void readingAWorldSectionDoesNotDisableChecksForAllWorlds() throws InvalidConfigurationException {
        final String yaml = """
            world-settings:
              world:
                mob-spawn-rang: 4
            """;
        final ServerAccess server = config -> {
            config.getConfigurationSection("world-settings.world"); // as done by legacy world name migrations
            config.getInt("world-settings.default.mob-spawn-range", 8);
        };

        assertEquals(
            List.of(ConfigurationFinding.unknownSetting("test.yml", "world-settings.world.mob-spawn-rang", "world-settings.world.mob-spawn-range")),
            check(yaml, null, WORLDS, server)
        );
    }

    @Test
    void settingsWrittenByTheServerAreNotReported() throws InvalidConfigurationException {
        final String yaml = """
            config-version: 13
            generated:
              a: 1
              b: 2
            """;
        final ServerAccess server = config -> {
            config.set("config-version", 13);
            config.set("generated", Map.of("a", 1, "b", 2));
        };

        assertEquals(List.of(), check(yaml, server));
    }

    @Test
    void defaultsDeclareSettingsThatAreReadLater() throws InvalidConfigurationException {
        final YamlConfiguration defaults = new YamlConfiguration();
        defaults.loadFromString("""
            spawn-limits:
              monsters: 70
              animals: 10
            """);
        final String yaml = """
            spawn-limits:
              monsters: 50
              monster: 40
            """;

        assertEquals(
            List.of(ConfigurationFinding.unknownSetting("test.yml", "spawn-limits.monster", "spawn-limits.monsters")),
            check(yaml, defaults, null, config -> {
            })
        );
    }

    @Test
    void mapDefaultsAcceptAnyKey() throws InvalidConfigurationException {
        final String yaml = """
            world-settings:
              default:
                tilt-delay:
                  UNSTABLE: 10
                  FULL: 100
            """;
        // Like Purpur's getMap on the first start: the map is registered as a single default value
        final ServerAccess server = config -> {
            config.getConfigurationSection("world-settings.default.tilt-delay-missing");
            config.addDefault("world-settings.default.tilt-delay", Map.of("FULL", 100));
        };

        assertEquals(List.of(), check(yaml, null, WORLDS, server));
    }

    @Test
    void valuesOfTheWrongTypeAreReported() throws InvalidConfigurationException {
        final String yaml = """
            settings:
              debug: yes please
              timeout: "30"
              worlds: world
              name: 42
            """;
        final ServerAccess server = config -> {
            config.getBoolean("settings.debug", false);
            config.getInt("settings.timeout", 60);
            config.getList("settings.worlds", List.of());
            config.getString("settings.name", "server");
        };

        assertEquals(
            List.of(
                ConfigurationFinding.invalidValue("test.yml", "settings.debug", "true or false", "\"yes please\""),
                ConfigurationFinding.invalidValue("test.yml", "settings.timeout", "a number", "\"30\""),
                ConfigurationFinding.invalidValue("test.yml", "settings.worlds", "a list", "\"world\"")
            ),
            check(yaml, server)
        );
    }

    @Test
    void settingsNotReadDuringStartupAreCheckedAgainstTheTypeOfTheirDefault() throws InvalidConfigurationException {
        final YamlConfiguration defaults = new YamlConfiguration();
        defaults.loadFromString("""
            settings:
              connection-throttle: 4000
              shutdown-message: Server closed
              allow-end: true
            """);
        // Like bukkit.yml settings that CraftServer only reads when a player joins
        final String yaml = """
            settings:
              connection-throttle: lots
              shutdown-message: 42
              allow-end: "no"
            """;

        assertEquals(
            List.of(
                ConfigurationFinding.invalidValue("test.yml", "settings.connection-throttle", "a number", "\"lots\""),
                ConfigurationFinding.invalidValue("test.yml", "settings.allow-end", "true or false", "\"no\"")
            ),
            check(yaml, defaults, null, config -> {
            })
        );
    }

    @Test
    void recordedReadsTakePrecedenceOverTheTypeOfTheDefault() throws InvalidConfigurationException {
        final YamlConfiguration defaults = new YamlConfiguration();
        defaults.loadFromString("""
            settings:
              limit: 10
            """);
        final String yaml = """
            settings:
              limit: unlimited
            """;
        // The server reads the setting as text, so any value is valid even though the default is a number
        final ServerAccess server = config -> config.getString("settings.limit");

        assertEquals(List.of(), check(yaml, defaults, null, server));
    }

    @Test
    void settingsReadAsTextAcceptAnyValue() throws InvalidConfigurationException {
        final String yaml = """
            settings:
              mode: invalid-but-text
            """;
        final ServerAccess server = config -> {
            config.getString("settings.mode", "default");
            config.getBoolean("settings.mode", false);
        };

        assertEquals(List.of(), check(yaml, server));
    }

    @Test
    void settingsReadWithConflictingTypesAreNotTypeChecked() throws InvalidConfigurationException {
        final String yaml = """
            settings:
              limit: unlimited
            """;
        final ServerAccess server = config -> {
            config.getBoolean("settings.limit", false);
            config.getInt("settings.limit", 10);
        };

        assertEquals(List.of(), check(yaml, server));
    }

    @Test
    void probedSettingsAreKnownButNotTypeChecked() throws InvalidConfigurationException {
        final String yaml = """
            commands:
              enable-spam-exclusions: maybe
            """;
        final ServerAccess server = config -> config.get("commands.enable-spam-exclusions");

        assertEquals(List.of(), check(yaml, server));
    }

    @Test
    void findingsFollowTheFileOrder() throws InvalidConfigurationException {
        final String yaml = """
            zzz-unknown: 1
            settings:
              timeout: "30"
            aaa-unknown: 2
            """;

        assertEquals(
            List.of(
                ConfigurationFinding.unknownSetting("test.yml", "zzz-unknown", null),
                ConfigurationFinding.invalidValue("test.yml", "settings.timeout", "a number", "\"30\""),
                ConfigurationFinding.unknownSetting("test.yml", "aaa-unknown", null)
            ),
            check(yaml, READ_SETTINGS)
        );
    }

    @Test
    void longValuesAreAbbreviated() {
        assertEquals("\"" + "x".repeat(37) + "...\"", ConfigurationChecker.describe("x".repeat(100)));
        assertEquals("a list", ConfigurationChecker.describe(List.of(1)));
        assertEquals("12", ConfigurationChecker.describe(12));
    }
}
