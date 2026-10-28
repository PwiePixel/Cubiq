package studio.cubiq.configcheck;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigurationCheckReportTest {

    @Test
    void cleanConfigurationsAreConfirmedInOneLine() {
        assertEquals(
            List.of("Configuration check: no unknown settings or invalid values in bukkit.yml, spigot.yml (12 ms)"),
            ConfigurationCheck.report(List.of("bukkit.yml", "spigot.yml"), List.of(), 12)
        );
    }

    @Test
    void nothingIsReportedWhenNothingWasChecked() {
        assertEquals(List.of(), ConfigurationCheck.report(List.of(), List.of(), 0));
    }

    @Test
    void findingsAreListedWithTheirFile() {
        final List<String> lines = ConfigurationCheck.report(List.of("spigot.yml"), List.of(
            ConfigurationFinding.unknownSetting("spigot.yml", "settings.timout", "settings.timeout"),
            ConfigurationFinding.invalidValue("spigot.yml", "settings.debug", "true or false", "\"maybe\"")
        ), 3);

        assertEquals(List.of(
            "Configuration check: found 2 problems in spigot.yml (3 ms). The following settings do not work as written:",
            "  spigot.yml: Unknown setting 'settings.timout' has no effect. Did you mean 'settings.timeout'?",
            "  spigot.yml: 'settings.debug' must be true or false, but is \"maybe\", so it is ignored.",
            "Fix or remove these settings. This check runs on every start and can be disabled with -Dcubiq.configCheck=false"
        ), lines);
    }

    @Test
    void unknownSectionsMentionHowManySettingsTheyContain() {
        assertEquals(
            "Unknown section 'messges' (1 setting) has no effect. Did you mean 'messages'?",
            ConfigurationFinding.unknownSection("spigot.yml", "messges", 1, "messages").message()
        );
        assertEquals(
            "Unknown section 'old' (3 settings) has no effect.",
            ConfigurationFinding.unknownSection("spigot.yml", "old", 3, null).message()
        );
    }

    @Test
    void replacedSettingsNameWhatReplacesThem() {
        assertEquals(
            "Setting 'pvp' has no effect anymore. Use the game rule pvp instead.",
            ConfigurationFinding.replacedSetting("server.properties", "pvp", "the game rule pvp").message()
        );
    }

    @Test
    void invalidValuesNameTheValueTheServerUsesIfItIsKnown() {
        assertEquals(
            "'white-list' must be true or false, but is \"true \" (with whitespace at the end), so the server uses false.",
            ConfigurationFinding.invalidValue("server.properties", "white-list", "true or false", "\"true \" (with whitespace at the end)", "false").message()
        );
        assertEquals(
            "'view-distance' must be a whole number, but is \"twelve\", so the server uses the default value 10.",
            ConfigurationFinding.invalidValue("server.properties", "view-distance", "a whole number", "\"twelve\"", "the default value 10").message()
        );
    }

    @Test
    void notesFollowTheMessage() {
        assertEquals(
            "Setting 'pvp' has no effect anymore. Use the game rule pvp instead. The server removed it from the file while starting.",
            ConfigurationFinding.replacedSetting("server.properties", "pvp", "the game rule pvp")
                .withNote("The server removed it from the file while starting.").message()
        );
    }

    @Test
    void longReportsAreTruncated() {
        final List<ConfigurationFinding> findings = new ArrayList<>();
        for (int i = 0; i < ConfigurationCheck.MAX_LOGGED_FINDINGS + 5; i++) {
            findings.add(ConfigurationFinding.unknownSetting("purpur.yml", "unknown-" + i, null));
        }

        final List<String> lines = ConfigurationCheck.report(List.of("purpur.yml"), findings, 1);

        assertEquals(ConfigurationCheck.MAX_LOGGED_FINDINGS + 3, lines.size());
        assertEquals("  ... and 5 more", lines.get(lines.size() - 2));
    }
}
