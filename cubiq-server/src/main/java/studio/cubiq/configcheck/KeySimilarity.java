package studio.cubiq.configcheck;

import java.util.Locale;
import org.jspecify.annotations.NullMarked;

/**
 * Edit distance between configuration keys, used to suggest the setting that was probably meant.
 */
@NullMarked
final class KeySimilarity {

    private KeySimilarity() {
    }

    /**
     * Normalizes a key so that differences in case and in the separator style ({@code a_b}, {@code a b},
     * {@code a-b}) do not count as edits.
     */
    static String normalize(final String key) {
        return key.toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
    }

    /**
     * The largest edit distance at which two keys of the given length are still considered similar. Short keys
     * must match almost exactly, otherwise nearly every short key would look like a typo of another one.
     */
    static int maxDistance(final int length) {
        if (length <= 3) {
            return 0;
        }
        if (length <= 6) {
            return 1;
        }
        if (length <= 12) {
            return 2;
        }
        return 3;
    }

    /**
     * Optimal string alignment distance: the number of insertions, deletions, substitutions and transpositions
     * of adjacent characters needed to turn {@code a} into {@code b}, where no substring is edited twice.
     */
    static int distance(final String a, final String b) {
        final int n = a.length();
        final int m = b.length();
        int[] previousPrevious = new int[m + 1];
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            current[0] = i;
            for (int j = 1; j <= m; j++) {
                final int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int value = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    value = Math.min(value, previousPrevious[j - 2] + 1);
                }
                current[j] = value;
            }
            final int[] recycled = previousPrevious;
            previousPrevious = previous;
            previous = current;
            current = recycled;
        }
        return previous[m];
    }

    /**
     * Returns whether {@code candidate} is close enough to {@code key} to be suggested as a correction, and if
     * so, how close it is.
     *
     * @return the distance between the normalized keys, or {@code -1} if the keys are not similar
     */
    static int similarity(final String key, final String candidate) {
        final String normalizedKey = normalize(key);
        final String normalizedCandidate = normalize(candidate);
        final int limit = maxDistance(Math.max(normalizedKey.length(), normalizedCandidate.length()));
        if (Math.abs(normalizedKey.length() - normalizedCandidate.length()) > limit) {
            return -1;
        }
        final int distance = distance(normalizedKey, normalizedCandidate);
        return distance <= limit ? distance : -1;
    }
}
