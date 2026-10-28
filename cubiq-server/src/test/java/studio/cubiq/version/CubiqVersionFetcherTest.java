package studio.cubiq.version;

import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.ServerBuildInfoImpl;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import studio.cubiq.Cubiq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        final String message = plain(CubiqVersionFetcher.statusMessage(buildInfo(OptionalInt.empty(), Optional.empty()), null));

        assertEquals("You are running a development build without version information", message);
    }

    @Test
    void buildWithGitCommitPointsToCubiqsReleasesInsteadOfUpstreamDownloads() {
        final Component status = CubiqVersionFetcher.statusMessage(buildInfo(OptionalInt.empty(), Optional.of("81a4230")), null);
        final String message = plain(status);

        assertTrue(message.startsWith("You are running a development build, not a release."), message);
        assertTrue(message.contains(Cubiq.RELEASES_URL), message);
        assertFalse(message.contains("papermc.io"), message);
        assertFalse(message.contains("purpurmc.org"), message);
        assertTrue(
            status.children().stream().anyMatch(child -> ClickEvent.openUrl(Cubiq.RELEASES_URL).equals(child.clickEvent())),
            "the releases link should be clickable"
        );
    }

    @Test
    void aReleaseNamesItsVersion() {
        final String message = plain(CubiqVersionFetcher.statusMessage(buildInfo(OptionalInt.of(3), Optional.of("81a4230")), "0.0.3"));

        assertEquals(
            "You are running Cubiq v0.0.3. Automatic update checks are not available for Cubiq yet. Check " + Cubiq.RELEASES_URL + " for newer releases.",
            message
        );
    }

    @Test
    void aBuildNumberAloneDoesNotMakeARelease() {
        final String message = plain(CubiqVersionFetcher.statusMessage(buildInfo(OptionalInt.of(12), Optional.empty()), null));

        assertTrue(message.startsWith("You are running a development build, not a release."), message);
    }

    @Test
    void theReleaseVersionComesFromTheManifest() {
        final Manifest release = new Manifest();
        release.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        release.getMainAttributes().putValue("Cubiq-Version", " 0.1.0 ");
        final Manifest development = new Manifest();
        development.getMainAttributes().putValue("Cubiq-Version", "");

        assertEquals("0.1.0", CubiqVersionFetcher.releaseVersion(release));
        assertNull(CubiqVersionFetcher.releaseVersion(development));
        assertNull(CubiqVersionFetcher.releaseVersion(new Manifest()));
        assertNull(CubiqVersionFetcher.releaseVersion(null));
    }

    @Test
    void neverCachesBecauseNothingIsFetchedRemotely() {
        assertTrue(new CubiqVersionFetcher().getCacheTime() < 0);
    }
}
