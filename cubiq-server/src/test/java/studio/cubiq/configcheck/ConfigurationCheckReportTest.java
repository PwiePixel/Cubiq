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
            "Configuration check: found 2 problems in spigot.yml (3 ms). The following settings have no effect:",
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
