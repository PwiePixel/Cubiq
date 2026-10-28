package studio.cubiq.configcheck;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeySimilarityTest {

    @Test
    void distanceCountsEdits() {
        assertEquals(0, KeySimilarity.distance("", ""));
        assertEquals(3, KeySimilarity.distance("", "abc"));
        assertEquals(3, KeySimilarity.distance("abc", ""));
        assertEquals(3, KeySimilarity.distance("kitten", "sitting"));
        assertEquals(1, KeySimilarity.distance("timout", "timeout"));
    }

    @Test
    void adjacentTranspositionIsOneEdit() {
        assertEquals(1, KeySimilarity.distance("ab", "ba"));
        assertEquals(1, KeySimilarity.distance("raduis", "radius"));
    }

    @Test
    void normalizationIgnoresCaseAndSeparatorStyle() {
        assertEquals("mob-spawn-range", KeySimilarity.normalize("Mob_Spawn Range"));
    }

    @Test
    void shortKeysMustMatchAlmostExactly() {
        assertEquals(0, KeySimilarity.similarity("foo", "FOO"));
        assertEquals(-1, KeySimilarity.similarity("foo", "bar"));
        assertEquals(-1, KeySimilarity.similarity("tps", "tpa"));
    }

    @Test
    void similarKeysAreRecognised() {
        assertEquals(1, KeySimilarity.similarity("timout", "timeout"));
        assertEquals(0, KeySimilarity.similarity("connection_throttle", "connection-throttle"));
        assertEquals(1, KeySimilarity.similarity("mob-spawn-rang", "mob-spawn-range"));
    }

    @Test
    void differentKeysAreNotSimilar() {
        assertEquals(-1, KeySimilarity.similarity("debug", "bungeecord"));
        assertEquals(-1, KeySimilarity.similarity("view-distance", "simulation-distance"));
    }

    @Test
    void allowedDistanceGrowsWithKeyLength() {
        assertEquals(0, KeySimilarity.maxDistance(3));
        assertEquals(1, KeySimilarity.maxDistance(6));
        assertEquals(2, KeySimilarity.maxDistance(12));
        assertEquals(3, KeySimilarity.maxDistance(30));
    }
}
