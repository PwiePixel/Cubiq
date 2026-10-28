package studio.cubiq.configcheck;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Records how the server accesses the paths of one configuration file.
 *
 * <p>Each path maps to a bit set of access flags. Recording is thread-safe and cheap, because it happens
 * for every configuration read while the server starts.</p>
 */
@NullMarked
final class ConfigurationUsage {

    /** The path was looked up without an expected type, for example by {@code contains} or {@code get}. */
    static final int PROBE = 1;
    /** The path was read as text. Any value is accepted because it is converted with {@code toString()}. */
    static final int READ_TEXT = 1 << 1;
    static final int READ_BOOLEAN = 1 << 2;
    static final int READ_NUMBER = 1 << 3;
    static final int READ_LIST = 1 << 4;
    /** The path was read as a section. Its children are processed by the caller, so any child key is valid. */
    static final int READ_SECTION = 1 << 5;
    static final int WRITE = 1 << 6;
    /** The path was written with a map or section value, so its children were created by the server. */
    static final int WRITE_SECTION = 1 << 7;

    static final int TYPED_READS = READ_BOOLEAN | READ_NUMBER | READ_LIST;
    static final int READS = PROBE | READ_TEXT | TYPED_READS | READ_SECTION;
    static final int SECTION_ACCESS = READ_SECTION | WRITE_SECTION;

    private final Map<String, Integer> access = new ConcurrentHashMap<>();

    void record(final String path, final int flags) {
        this.access.merge(path, flags, (previous, added) -> previous | added);
    }

    /**
     * Returns the read flag of the getter that matches the type of {@code value}, for example
     * {@link #READ_NUMBER} for a number. Used to derive the expected type of a setting from its default value.
     */
    static int typeOf(final @Nullable Object value) {
        if (value instanceof Boolean) {
            return READ_BOOLEAN;
        }
        if (value instanceof Number) {
            return READ_NUMBER;
        }
        if (value instanceof List<?>) {
            return READ_LIST;
        }
        if (value instanceof String) {
            return READ_TEXT;
        }
        return PROBE;
    }

    Map<String, Integer> snapshot() {
        return Map.copyOf(this.access);
    }

    void clear() {
        this.access.clear();
    }
}
