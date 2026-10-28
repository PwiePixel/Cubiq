package studio.cubiq.version;

import com.destroystokyo.paper.VersionHistoryManager;
import com.destroystokyo.paper.util.VersionFetcher;
import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import studio.cubiq.Cubiq;

import static net.kyori.adventure.text.Component.text;

/**
 * Supplies the update status shown by {@code /version}.
 *
 * <p>Cubiq does not publish builds through a release API yet, so this fetcher never contacts a
 * remote service. Querying the Paper or Purpur APIs for a Cubiq build would produce misleading
 * results, so the message only describes the local build and links to the project page.</p>
 */
@NullMarked
public final class CubiqVersionFetcher implements VersionFetcher {

    @Override
    public long getCacheTime() {
        // Nothing is fetched remotely, so building the message is cheap and needs no caching
        return -1;
    }

    @Override
    public Component getVersionMessage() {
        final Component status = statusMessage(ServerBuildInfo.buildInfo());
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

    static Component statusMessage(final ServerBuildInfo buildInfo) {
        if (buildInfo.buildNumber().isEmpty() && buildInfo.gitCommit().isEmpty()) {
            return text("You are running a development build without version information", NamedTextColor.YELLOW);
        }
        return text("Automatic update checks are not available for Cubiq yet. Check ", NamedTextColor.YELLOW)
            .append(text(Cubiq.REPOSITORY_URL, NamedTextColor.GOLD)
                .hoverEvent(text("Click to open", NamedTextColor.WHITE))
                .clickEvent(ClickEvent.openUrl(Cubiq.REPOSITORY_URL)))
            .append(text(" for new builds.", NamedTextColor.YELLOW));
    }

    private static @Nullable String previousVersion() {
        final VersionHistoryManager.@Nullable VersionData data = VersionHistoryManager.INSTANCE.getVersionData();
        return data == null ? null : data.getOldVersion();
    }
}
