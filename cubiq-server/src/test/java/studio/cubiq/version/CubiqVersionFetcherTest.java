package studio.cubiq.version;

import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.ServerBuildInfoImpl;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import studio.cubiq.Cubiq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubiqVersionFetcherTest {

    private static ServerBuildInfo buildInfo(final OptionalInt buildNumber, final Optional<String> gitCommit) {
        return new ServerBuildInfoImpl(
            ServerBuildInfo.BRAND_CUBIQ_ID,
            "Cubiq",
            "26.3",
            "26.3",
            buildNumber,
            Instant.EPOCH,
            Optional.of("main"),
            gitCommit
        );
    }

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void developmentBuildWithoutVersionInformation() {
        final String message = plain(CubiqVersionFetcher.statusMessage(buildInfo(OptionalInt.empty(), Optional.empty())));

        assertEquals("You are running a development build without version information", message);
    }

    @Test
    void buildWithGitCommitPointsToCubiqInsteadOfUpstreamDownloads() {
        final Component status = CubiqVersionFetcher.statusMessage(buildInfo(OptionalInt.empty(), Optional.of("81a4230")));
        final String message = plain(status);

        assertTrue(message.contains(Cubiq.REPOSITORY_URL), message);
        assertFalse(message.contains("papermc.io"), message);
        assertFalse(message.contains("purpurmc.org"), message);
        assertTrue(
            status.children().stream().anyMatch(child -> ClickEvent.openUrl(Cubiq.REPOSITORY_URL).equals(child.clickEvent())),
            "the repository link should be clickable"
        );
    }

    @Test
    void numberedBuildIsNotReportedAsDevelopmentBuild() {
        final String message = plain(CubiqVersionFetcher.statusMessage(buildInfo(OptionalInt.of(12), Optional.empty())));

        assertTrue(message.startsWith("Automatic update checks are not available for Cubiq yet."), message);
    }

    @Test
    void neverCachesBecauseNothingIsFetchedRemotely() {
        assertTrue(new CubiqVersionFetcher().getCacheTime() < 0);
    }
}
