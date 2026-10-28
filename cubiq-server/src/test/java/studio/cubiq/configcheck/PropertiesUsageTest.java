package studio.cubiq.configcheck;

import java.util.Map;
import java.util.Set;
import net.minecraft.util.StringRepresentable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PropertiesUsageTest {

    private enum Mode implements StringRepresentable {
        SURVIVAL("survival"),
        CREATIVE("creative");

        private final String name;

        Mode(final String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    private enum Broken implements StringRepresentable {
        VALUE;

        @Override
        public String getSerializedName() {
            throw new IllegalStateException("broken");
        }
    }

    @BeforeEach
    @AfterEach
    void reset() {
        ConfigurationCheck.resetForTests();
    }

    @Test
    void nothingIsRecordedOnceTheCheckHasRun() {
        // For example when a plugin changes the maximum number of players, which creates new settings objects
        ConfigurationCheck.runAfterStartup();
        final PropertiesUsage usage = new PropertiesUsage();

        usage.read("max-players");
        assertNull(usage.parsed("max-players", "lots", null, 20));

        assertEquals(Set.of(), usage.readKeys());
        assertEquals(Map.of(), usage.misreads());
    }

    @Test
    void readSettingsAreRecorded() {
        final PropertiesUsage usage = new PropertiesUsage();

        usage.read("max-players");
        usage.read("motd");
        usage.read("max-players");

        assertEquals(Set.of("max-players", "motd"), usage.readKeys());
    }

    @Test
    void aValueThatCouldNotBeConvertedIsReportedWithTheDefault() {
        final PropertiesUsage usage = new PropertiesUsage();

        // A space at the end is part of the value, so Integer.parseInt fails and Minecraft uses the default
        assertNull(usage.parsed("max-players", "100 ", null, 20));

        assertEquals(Map.of("max-players", new PropertiesUsage.Misread("100 ", "a whole number", true, null)), usage.misreads());
    }

    @Test
    void trueOrFalseSettingsReportEveryOtherValue() {
        final PropertiesUsage usage = new PropertiesUsage();

        // Boolean.valueOf reads both as false
        usage.parsed("white-list", "true ", false, true);
        usage.parsed("online-mode", "yes", false, true);

        assertEquals(Map.of(
            "white-list", new PropertiesUsage.Misread("true ", "true or false", false, "false"),
            "online-mode", new PropertiesUsage.Misread("yes", "true or false", false, "false")
        ), usage.misreads());
    }

    @Test
    void valuesThatAreReadAsWhatTheyMeanAreNotReported() {
        final PropertiesUsage usage = new PropertiesUsage();

        // Read as false and survival, which is what they mean
        usage.parsed("white-list", "false ", false, true);
        usage.parsed("gamemode", "survival ", Mode.SURVIVAL, Mode.SURVIVAL);

        assertEquals(Map.of(), usage.misreads());
    }

    @Test
    void legacySettingsHaveNoDefault() {
        final PropertiesUsage usage = new PropertiesUsage();

        // Minecraft reads announce-player-achievements only to move it to a game rule
        usage.parsed("announce-player-achievements", "yes", false, null);

        assertEquals(Map.of("announce-player-achievements", new PropertiesUsage.Misread("yes", "true or false", false, "false")), usage.misreads());
    }

    @Test
    void trueAndFalseAreValidInAnyCase() {
        final PropertiesUsage usage = new PropertiesUsage();

        usage.parsed("white-list", "TRUE", true, false);
        usage.parsed("online-mode", "False", false, true);

        assertEquals(Map.of(), usage.misreads());
    }

    @Test
    void aNameThatFallsBackToAnotherConstantIsReported() {
        final PropertiesUsage usage = new PropertiesUsage();

        // GameType.byName returns survival for names it does not know, and names are case-sensitive
        usage.parsed("gamemode", "Creative", Mode.SURVIVAL, Mode.SURVIVAL);

        assertEquals(Map.of("gamemode", new PropertiesUsage.Misread("Creative", "one of survival, creative", false, "survival")), usage.misreads());
    }

    @Test
    void namesAndLegacyIdsOfConstantsAreValid() {
        final PropertiesUsage usage = new PropertiesUsage();

        usage.parsed("gamemode", "creative", Mode.CREATIVE, Mode.SURVIVAL);
        usage.parsed("default-gamemode", "1", Mode.CREATIVE, Mode.SURVIVAL);

        assertEquals(Map.of(), usage.misreads());
    }

    @Test
    void otherValuesAreNotJudged() {
        final PropertiesUsage usage = new PropertiesUsage();

        usage.parsed("motd", "A Minecraft Server ", "A Minecraft Server ", "A Minecraft Server");
        // Values that Minecraft limits to a range are used, only limited
        usage.parsed("max-world-size", "50000000", 29999984, 29999984);

        assertEquals(Map.of(), usage.misreads());
    }

    @Test
    void theConvertedValueIsReturnedUnchanged() {
        final Object converted = new Object();

        assertSame(converted, new PropertiesUsage().parsed("generator-settings", "{}", converted, new Object()));
    }

    @Test
    void anErrorInTheCheckDoesNotAffectLoading() {
        final PropertiesUsage usage = new PropertiesUsage();

        assertSame(Broken.VALUE, usage.parsed("broken", "value", Broken.VALUE, Broken.VALUE));
        assertEquals(Map.of(), usage.misreads());
    }

    @Test
    void theExpectedValueIsDescribedByTheTypeOfTheSetting() {
        assertEquals("true or false", PropertiesUsage.expected(true));
        assertEquals("a whole number", PropertiesUsage.expected(20));
        assertEquals("a whole number", PropertiesUsage.expected(60000L));
        assertEquals("one of survival, creative", PropertiesUsage.expected(Mode.CREATIVE));
        assertEquals("a valid value", PropertiesUsage.expected("deflate"));
        assertEquals("a valid value", PropertiesUsage.expected(null));
    }
}
