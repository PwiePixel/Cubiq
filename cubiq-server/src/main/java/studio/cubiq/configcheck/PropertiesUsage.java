package studio.cubiq.configcheck;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import studio.cubiq.Cubiq;

/**
 * Records how Minecraft reads {@code server.properties}: which settings it reads, and which values it does not use as
 * they are written. Each of Minecraft's {@code Settings} objects, which hold the settings of the file, has one, and
 * {@link ServerPropertiesCheck} evaluates it. Only objects created while the configuration check is still going to
 * run record anything.
 *
 * <p>Minecraft keeps settings that it does not read in the file without using them. When it cannot convert a value,
 * it uses the default value instead for most settings, and it reads every value of a {@code true}/{@code false}
 * setting other than {@code true} as {@code false}, for example {@code "true "} with a space at the end. Neither is
 * logged.</p>
 */
@NullMarked
public final class PropertiesUsage {

    static final String WHOLE_NUMBER = "a whole number";
    static final String ANY_VALUE = "a valid value";

    /** Minecraft creates new settings objects when settings change at runtime, which the check does not need. */
    private final boolean recording = ConfigurationCheck.isPending();
    private final Set<String> readKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, Misread> misreads = new ConcurrentHashMap<>();

    /**
     * A value that the server does not use as it is written.
     *
     * @param value       the value in the file
     * @param expected    a description of the values the server accepts
     * @param defaultUsed whether the server uses the default value instead; otherwise it read the value as another
     *                    value
     * @param converted   the value the server read, if it read one
     */
    record Misread(String value, String expected, boolean defaultUsed, @Nullable String converted) {
    }

    /**
     * Called by Minecraft for every setting it reads.
     *
     * @param key the setting
     */
    public void read(final String key) {
        if (this.recording) {
            this.readKeys.add(key);
        }
    }

    /**
     * Called by Minecraft when it has converted the value of a setting. Never throws.
     *
     * @param key          the setting
     * @param value        the value in the file
     * @param parsed       the converted value, or {@code null} if Minecraft could not convert the value
     * @param defaultValue the value Minecraft uses if it could not convert the value, or {@code null} for legacy
     *                     settings, which Minecraft reads once and removes from the file
     * @param <V>          the type of the setting
     * @return {@code parsed}
     */
    public <V> @Nullable V parsed(final String key, final String value, final @Nullable V parsed, final @Nullable V defaultValue) {
        if (!this.recording) {
            return parsed;
        }
        try {
            final Misread misread = misread(value, parsed, defaultValue);
            if (misread != null) {
                this.misreads.put(key, misread);
            }
        } catch (final RuntimeException | LinkageError ex) {
            // A bug in this diagnostic must never affect loading the settings
            Cubiq.LOGGER.debug("The configuration check could not evaluate the value of {}", key, ex);
        }
        return parsed;
    }

    Set<String> readKeys() {
        return this.readKeys;
    }

    Map<String, Misread> misreads() {
        return this.misreads;
    }

    static @Nullable Misread misread(final String value, final @Nullable Object parsed, final @Nullable Object defaultValue) {
        if (parsed == null) {
            // Whether the default is what the value means is decided by ServerPropertiesCheck, which knows the default
            // as Minecraft writes it
            return new Misread(value, expected(defaultValue), true, null);
        }
        if (parsed instanceof Boolean) {
            // Boolean.valueOf reads everything except "true" in any case as false, also "true " with a space at the end
            return value.strip().equalsIgnoreCase(parsed.toString()) ? null : new Misread(value, "true or false", false, parsed.toString());
        }
        if (parsed instanceof Enum<?> && parsed instanceof StringRepresentable named
            && !isInteger(value) && !value.strip().equalsIgnoreCase(named.getSerializedName())) {
            // Some settings fall back to a constant for names they do not know, for example the game mode to survival.
            // Numbers are the legacy ids of the constants.
            return new Misread(value, expected(parsed), false, named.getSerializedName());
        }
        return null;
    }

    /**
     * Describes the values a setting accepts, based on the type of its value.
     */
    static String expected(final @Nullable Object value) {
        if (value instanceof Boolean) {
            return "true or false";
        }
        if (value instanceof Integer || value instanceof Long) {
            return WHOLE_NUMBER;
        }
        if (value instanceof Enum<?> constant && value instanceof StringRepresentable) {
            return "one of " + Arrays.stream(constant.getDeclaringClass().getEnumConstants())
                .map(other -> ((StringRepresentable) other).getSerializedName())
                .collect(Collectors.joining(", "));
        }
        return ANY_VALUE;
    }

    static boolean isInteger(final String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (final NumberFormatException ex) {
            return false;
        }
    }
}
