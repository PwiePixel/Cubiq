package studio.cubiq.lifecycle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Writes how long starting and stopping the server took to a JSON file, for tools such as benchmarks.
 *
 * <p>The file is written when the server has started and again when it has stopped, and replaced atomically where the
 * file system supports it. Times are in milliseconds with three decimals. Format:</p>
 * <pre>{@code
 * {
 *   "formatVersion": 1,
 *   "startup": {
 *     "totalMillis": 15348.0,
 *     "steps": [
 *       {"name": "bootstrapping", "millis": 4102.512},
 *       {"name": "worlds", "millis": 7905.03, "details": [{"name": "world", "millis": 3001.25}]},
 *       {"name": "other", "millis": 401.7}
 *     ],
 *     "plugins": [{"name": "Example", "loadMillis": 12.5, "enableMillis": 310.25}]
 *   },
 *   "shutdown": {"totalMillis": 2104.9, "steps": [], "plugins": [{"name": "Example", "disableMillis": 52.0}]}
 * }
 * }</pre>
 * <p>{@code other} is the time that no step accounts for. A part is missing if the server has not started or stopped,
 * for example the shutdown while the server runs, or the startup if it failed.</p>
 */
final class LifecycleReport {

    /** Changes when a field changes its meaning or is removed; new fields can be added without a new version. */
    static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private LifecycleReport() {
    }

    /**
     * Writes the report.
     *
     * @param path     the file
     * @param startup  how long the startup took, or {@code null}
     * @param shutdown how long the shutdown took, or {@code null}
     * @throws IOException if the file cannot be written
     */
    static void write(final Path path, final @Nullable TimingReport startup, final @Nullable TimingReport shutdown) throws IOException {
        final Path file = path.toAbsolutePath();
        final Path directory = file.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, toJson(startup, shutdown), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException ex) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Returns the report as JSON.
     *
     * @param startup  how long the startup took, or {@code null}
     * @param shutdown how long the shutdown took, or {@code null}
     * @return the JSON text, ending with a line break
     */
    static String toJson(final @Nullable TimingReport startup, final @Nullable TimingReport shutdown) {
        final JsonObject report = new JsonObject();
        report.addProperty("formatVersion", FORMAT_VERSION);
        if (startup != null) {
            report.add("startup", toJson(startup));
        }
        if (shutdown != null) {
            report.add("shutdown", toJson(shutdown));
        }
        return GSON.toJson(report) + "\n";
    }

    private static JsonObject toJson(final TimingReport timing) {
        final JsonObject object = new JsonObject();
        object.addProperty("totalMillis", millis(timing.totalNanos()));
        final JsonArray steps = new JsonArray();
        for (final Breakdown.Entry entry : timing.steps()) {
            final JsonObject step = named(entry.label(), "millis", entry.nanos());
            if (!entry.details().isEmpty()) {
                final JsonArray details = new JsonArray();
                for (final Breakdown.Detail detail : entry.details()) {
                    details.add(named(detail.subject(), "millis", detail.nanos()));
                }
                step.add("details", details);
            }
            steps.add(step);
        }
        steps.add(named("other", "millis", timing.otherNanos()));
        object.add("steps", steps);

        final Map<String, Map<PluginStep, Long>> byPlugin = new LinkedHashMap<>();
        for (final PluginTiming plugin : timing.plugins()) {
            byPlugin.computeIfAbsent(plugin.pluginName(), _ -> new EnumMap<>(PluginStep.class)).merge(plugin.step(), plugin.nanos(), Long::sum);
        }
        final JsonArray plugins = new JsonArray();
        byPlugin.forEach((name, pluginSteps) -> {
            final JsonObject plugin = new JsonObject();
            plugin.addProperty("name", name);
            pluginSteps.forEach((step, nanos) -> plugin.addProperty(step.noun() + "Millis", millis(nanos)));
            plugins.add(plugin);
        });
        object.add("plugins", plugins);
        return object;
    }

    private static JsonObject named(final String name, final String key, final long nanos) {
        final JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.addProperty(key, millis(nanos));
        return object;
    }

    /**
     * Converts nanoseconds to milliseconds with three decimals.
     *
     * @param nanos the time in nanoseconds
     * @return the time in milliseconds
     */
    static double millis(final long nanos) {
        return Math.round(nanos / 1_000.0) / 1_000.0;
    }
}
