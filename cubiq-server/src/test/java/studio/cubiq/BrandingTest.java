package studio.cubiq;

import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.ServerBuildInfoImpl;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandingTest {

    private static ServerBuildInfo cubiqBuild() {
        return new ServerBuildInfoImpl(
            ServerBuildInfo.BRAND_CUBIQ_ID,
            "Cubiq",
            "26.3",
            "26.3",
            OptionalInt.empty(),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        );
    }

    @Test
    void cubiqBrandKey() {
        assertEquals(Key.key("cubiqstudio", "cubiq"), ServerBuildInfo.BRAND_CUBIQ_ID);
    }

    @Test
    void cubiqIsCompatibleWithItselfAndItsUpstreams() {
        final ServerBuildInfo buildInfo = cubiqBuild();

        assertTrue(buildInfo.isBrandCompatible(ServerBuildInfo.BRAND_CUBIQ_ID));
        assertTrue(buildInfo.isBrandCompatible(ServerBuildInfo.BRAND_PURPUR_ID), "plugins checking for Purpur must keep working");
        assertTrue(buildInfo.isBrandCompatible(ServerBuildInfo.BRAND_PAPER_ID), "plugins checking for Paper must keep working");
    }

    @Test
    void cubiqIsNotCompatibleWithUnrelatedBrands() {
        assertFalse(cubiqBuild().isBrandCompatible(Key.key("example", "other")));
    }

    @Test
    void issueTrackerBelongsToCubiq() {
        assertEquals(Cubiq.REPOSITORY_URL + "/issues", Cubiq.ISSUES_URL);
        assertTrue(Cubiq.ISSUES_URL.startsWith("https://github.com/PwiePixel/Cubiq"));
    }
}
