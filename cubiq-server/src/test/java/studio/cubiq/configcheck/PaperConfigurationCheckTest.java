package studio.cubiq.configcheck;

import io.leangen.geantyref.TypeToken;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PaperConfigurationCheckTest {

    private static final String FILE = "config/paper-world-defaults.yml";

    private static ConfigurationNode node(final Object values) {
        // Maps and lists become child nodes, as when a file is loaded
        return BasicConfigurationNode.root().raw(values);
    }

    private static List<ConfigurationFinding> check(final Object file, final Object known) throws SerializationException {
        return PaperConfigurationCheck.unknownSettings(FILE, node(file), node(known));
    }

    @BeforeEach
    @AfterEach
    void reset() {
        ConfigurationCheck.resetForTests();
    }

    @Test
    void settingsThatPaperKnowsAreNotReported() throws SerializationException {
        final Map<String, Object> settings = Map.of("chunks", Map.of("auto-save-interval", "default", "max-auto-save-chunks-per-tick", 24));

        assertEquals(List.of(), check(settings, settings));
    }

    @Test
    void anUnknownSettingIsReportedWithTheMostSimilarSetting() throws SerializationException {
        final List<ConfigurationFinding> findings = check(
            Map.of("chunks", Map.of("auto-save-intervall", 6000)),
            Map.of("chunks", Map.of("auto-save-interval", "default", "max-auto-save-chunks-per-tick", 24))
        );

        assertEquals(List.of(ConfigurationFinding.unknownSetting(FILE, "chunks.auto-save-intervall", "chunks.auto-save-interval")), findings);
    }

    @Test
    void anUnknownSectionIsReportedOnceWithItsNumberOfSettings() throws SerializationException {
        final List<ConfigurationFinding> findings = check(
            Map.of("unknown-section", Map.of("a", 1, "b", Map.of("c", 2, "d", 3))),
            Map.of("chunks", Map.of("auto-save-interval", "default"))
        );

        assertEquals(List.of(ConfigurationFinding.unknownSection(FILE, "unknown-section", 3, null)), findings);
    }

    @Test
    void aSettingInTheWrongSectionIsSuggestedWhereItBelongs() throws SerializationException {
        final List<ConfigurationFinding> findings = check(
            Map.of("entities", Map.of("max-auto-save-chunks-per-tick", 12)),
            Map.of("entities", Map.of("armor-stands", Map.of("tick", true)), "chunks", Map.of("max-auto-save-chunks-per-tick", 24))
        );

        assertEquals(
            List.of(ConfigurationFinding.unknownSetting(FILE, "entities.max-auto-save-chunks-per-tick", "chunks.max-auto-save-chunks-per-tick")),
            findings
        );
    }

    @Test
    void registryKeysWithAndWithoutTheMinecraftNamespaceAreTheSame() throws SerializationException {
        // Paper reads entries of registry maps with or without the namespace and writes them back in one form
        final List<ConfigurationFinding> findings = check(
            Map.of("entity-per-chunk-save-limit", Map.of("minecraft:experience_orb", 16, "Arrow", 16)),
            Map.of("entity-per-chunk-save-limit", Map.of("experience_orb", 16, "arrow", 16))
        );

        assertEquals(List.of(), findings);
    }

    @Test
    void mapEntriesThatPaperCouldNotReadAreReported() throws SerializationException {
        // Paper leaves out entries it cannot read, for example a misspelled mob category
        final List<ConfigurationFinding> findings = check(
            Map.of("spawn-limits", Map.of("monster", 70, "monsters", 10)),
            Map.of("spawn-limits", Map.of("monster", 70, "creature", -1))
        );

        assertEquals(List.of(ConfigurationFinding.unknownSetting(FILE, "spawn-limits.monsters", "spawn-limits.monster")), findings);
    }

    @Test
    void theVersionOfTheFileIsNoSetting() throws SerializationException {
        assertEquals(List.of(), check(Map.of("_version", 31), Map.of("chunks", Map.of("auto-save-interval", "default"))));
    }

    @Test
    void listsAreNotComparedEntryByEntry() throws SerializationException {
        assertEquals(List.of(), check(Map.of("hidden-items", List.of("stone")), Map.of("hidden-items", List.of())));
    }

    @Test
    void filesInTheServerDirectoryAreNamedRelativeToIt() {
        assertEquals(
            Path.of("config", "paper-global.yml").toString(),
            CheckedFile.displayName(Path.of("config", "paper-global.yml").toAbsolutePath())
        );
    }

    @Test
    void loadedFilesAreCheckedWhileTheCheckIsPending() throws SerializationException {
        final ConfigurationNode file = node(Map.of("view-distance", 1, "colour", 2));

        PaperConfigurationCheck.loaded(Path.of(FILE), file, new TypeToken<Map<String, Integer>>() {}.getType(), Map.of("view-distance", 1));

        assertEquals(
            List.of(new CheckedFile(FILE, List.of(ConfigurationFinding.unknownSetting(FILE, "colour", null)))),
            PaperConfigurationCheck.drain()
        );
        assertEquals(List.of(), PaperConfigurationCheck.drain(), "drained");
    }

    @Test
    void aFileThatIsLoadedAgainBeforeTheCheckIsReportedOnce() throws SerializationException {
        // For example when a plugin runs /reload in a task before the check
        final ConfigurationNode file = node(Map.of("view-distance", 1, "colour", 2));
        final Type type = new TypeToken<Map<String, Integer>>() {}.getType();

        PaperConfigurationCheck.loaded(Path.of(FILE), file, type, Map.of("view-distance", 1));
        PaperConfigurationCheck.loaded(Path.of(FILE), file, type, Map.of("view-distance", 1));

        assertEquals(
            List.of(new CheckedFile(FILE, List.of(ConfigurationFinding.unknownSetting(FILE, "colour", null)))),
            PaperConfigurationCheck.drain()
        );
    }

    @Test
    void filesLoadedAfterTheCheckAreNotChecked() throws SerializationException {
        // For example when Paper's configuration is reloaded
        ConfigurationCheck.runAfterStartup();

        PaperConfigurationCheck.loaded(Path.of(FILE), node(Map.of("unknown", 2)), new TypeToken<Map<String, Integer>>() {}.getType(), Map.of());

        assertEquals(List.of(), PaperConfigurationCheck.drain());
    }

    private record Unserializable() {
    }

    @Test
    void aFileThatCannotBeCheckedIsReportedWithoutAnException() {
        final ConfigurationOptions options = ConfigurationOptions.defaults().serializers(serializers -> serializers.register(
            Unserializable.class,
            new TypeSerializer<Unserializable>() {
                @Override
                public Unserializable deserialize(final Type type, final ConfigurationNode node) {
                    return new Unserializable();
                }

                @Override
                public void serialize(final Type type, final Unserializable value, final ConfigurationNode node) throws SerializationException {
                    throw new SerializationException("cannot be written");
                }
            }
        ));
        final ConfigurationNode file = BasicConfigurationNode.root(options).raw(Map.of("a", 1));

        PaperConfigurationCheck.loaded(Path.of(FILE), file, Unserializable.class, new Unserializable());

        final List<CheckedFile> results = PaperConfigurationCheck.drain();
        assertEquals(1, results.size());
        assertNull(results.getFirst().findings());
    }
}
