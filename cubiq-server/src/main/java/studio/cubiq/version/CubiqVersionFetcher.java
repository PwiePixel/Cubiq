package studio.cubiq.version;

import com.destroystokyo.paper.VersionHistoryManager;
import com.destroystokyo.paper.util.VersionFetcher;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.util.JarManifests;
import java.util.jar.Manifest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.craftbukkit.CraftServer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import studio.cubiq.Cubiq;

import static net.kyori.adventure.text.Component.text;

/**
 * Supplies the update status shown by {@code /version}.
 *
 * <p>This fetcher never contacts a remote service: querying the Paper or Purpur APIs for a Cubiq build would produce
 * misleading results, and Cubiq does not check its releases for updates yet. The message describes the local build,
 * with its release version if it is a release, and links to Cubiq's releases.</p>
 */
@NullMarked
public final class CubiqVersionFetcher implements VersionFetcher {

    /** The attribute of the server jar's manifest that holds the version of a release, which CI sets for releases. */
    static final String RELEASE_VERSION_ATTRIBUTE = "Cubiq-Version";

    @Override
    public long getCacheTime() {
        // Nothing is fetched remotely, so building the message is cheap and needs no caching
        return -1;
    }

    @Override
    public Component getVersionMessage() {
        final Component status = statusMessage(ServerBuildInfo.buildInfo(), releaseVersion(JarManifests.manifest(CraftServer.class)));
        final @Nullable String previousVersion = previousVersion();
        if (previousVersion == null) {
            return status;
        }
        return Component.textOfChildren(
            status,
            Component.newline(),
            text("Previous version: " + previousVersion, NamedTextColor.GRAY, TextDecoration.ITALIC)
        );
    }

    static Component statusMessage(final ServerBuildInfo buildInfo, final @Nullable String releaseVersion) {
        if (releaseVersion != null) {
            return text("You are running Cubiq v" + releaseVersion + ". Automatic update checks are not available for Cubiq yet. Check ", NamedTextColor.YELLOW)
                .append(releasesLink())
                .append(text(" for newer releases.", NamedTextColor.YELLOW));
        }
        if (buildInfo.buildNumber().isEmpty() && buildInfo.gitCommit().isEmpty()) {
            return text("You are running a development build without version information", NamedTextColor.YELLOW);
        }
        return text("You are running a development build, not a release. Automatic update checks are not available for Cubiq yet. Check ", NamedTextColor.YELLOW)
            .append(releasesLink())
            .append(text(" for releases.", NamedTextColor.YELLOW));
    }

    private static Component releasesLink() {
        return text(Cubiq.RELEASES_URL, NamedTextColor.GOLD)
            .hoverEvent(text("Click to open", NamedTextColor.WHITE))
            .clickEvent(ClickEvent.openUrl(Cubiq.RELEASES_URL));
    }

    /**
     * Returns the version of the release that the server jar belongs to.
     *
     * @param manifest the manifest of the server jar, or {@code null} if it cannot be read
     * @return the version, for example {@code 0.0.1}, or {@code null} if the jar is no release
     */
    static @Nullable String releaseVersion(final @Nullable Manifest manifest) {
        final String version = manifest == null ? null : manifest.getMainAttributes().getValue(RELEASE_VERSION_ATTRIBUTE);
        return version == null || version.isBlank() ? null : version.strip();
    }

    private static @Nullable String previousVersion() {
        final VersionHistoryManager.@Nullable VersionData data = VersionHistoryManager.INSTANCE.getVersionData();
        return data == null ? null : data.getOldVersion();
    }
}
