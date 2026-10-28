package studio.cubiq.configcheck;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A {@link YamlConfiguration} that records which paths the server reads and writes.
 *
 * <p>CraftBukkit, Spigot and Purpur read their settings through the Bukkit configuration API and silently fall
 * back to a default when a setting is missing, misspelled or has the wrong type. Recording the paths the server
 * actually uses lets {@link ConfigurationCheck} point out settings that have no effect.</p>
 *
 * <p>Only accesses made by callers are recorded. {@code MemorySection} resolves nested paths, and implements
 * {@code set}, {@code createSection} and the comment accessors, by calling {@code contains} and
 * {@code getConfigurationSection} on this object again. A per-thread depth counter therefore suppresses recording
 * while one of the overridden methods is already running, so that intermediate sections are not mistaken for
 * settings the caller consumed.</p>
 *
 * <p>The expected type of a setting is only taken from the typed getters ({@code getBoolean}, {@code getInt},
 * {@code getString}, ...). A direct {@code get(path, def)} call is recorded without a type, because its fallback
 * value can be any object, for example the value of another setting.</p>
 */
@NullMarked
public final class TrackedYamlConfiguration extends YamlConfiguration {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private final String name;
    private final ConfigurationUsage usage = new ConfigurationUsage();
    private volatile boolean recording = ConfigurationCheck.isPending();
    private volatile @Nullable File file;

    /**
     * Creates an empty configuration and registers it for the configuration check. Configurations created after the
     * check has run, for example by {@code /reload}, do not record anything.
     *
     * @param name the name of the configuration file, used to select check rules and in messages
     */
    public TrackedYamlConfiguration(final String name) {
        this.name = name;
        ConfigurationCheck.register(this);
    }

    /**
     * Equivalent of {@link YamlConfiguration#loadConfiguration(File)} that returns a tracked configuration.
     *
     * @param name the name of the configuration file
     * @param file the file to load
     * @return the loaded configuration, or an empty one if the file could not be read
     */
    public static TrackedYamlConfiguration loadConfiguration(final String name, final File file) {
        final TrackedYamlConfiguration config = new TrackedYamlConfiguration(name);
        try {
            config.load(file);
        } catch (final FileNotFoundException ignored) {
            // The server writes a new file with the default values
        } catch (final IOException | InvalidConfigurationException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Cannot load " + file, ex);
        }
        return config;
    }

    String name() {
        return this.name;
    }

    @Nullable File file() {
        return this.file;
    }

    ConfigurationUsage usage() {
        return this.usage;
    }

    void stopRecording() {
        this.recording = false;
    }

    void releaseUsage() {
        this.usage.clear();
    }

    /**
     * Enters an overridden method.
     *
     * @return whether this call was made by a caller and should be recorded
     */
    private boolean enter() {
        return DEPTH.get()[0]++ == 0 && this.recording;
    }

    private static void exit() {
        DEPTH.get()[0]--;
    }

    @Override
    public @Nullable Object get(final String path) {
        final boolean record = this.enter();
        try {
            if (record) {
                this.usage.record(path, ConfigurationUsage.PROBE);
            }
            return super.get(path);
        } finally {
            exit();
        }
    }

    @Override
    public @Nullable Object get(final String path, final @Nullable Object def) {
        final boolean record = this.enter();
        try {
            if (record) {
                this.usage.record(path, ConfigurationUsage.PROBE);
            }
            return super.get(path, def);
        } finally {
            exit();
        }
    }

    // The single-argument typed getters delegate to these, so every typed read is recorded here

    @Override
    public @Nullable String getString(final String path, final @Nullable String def) {
        final boolean record = this.enter();
        try {
            if (record) {
                this.usage.record(path, ConfigurationUsage.READ_TEXT);
            }
            return super.getString(path, def);
        } finally {
            exit();
        }
    }

    @Override
    public int getInt(final String path, final int def) {
        final boolean record = this.enter();
        try {
            if (record) {
                this.usage.record(path, ConfigurationUsage.READ_NUMBER);
            }
            return super.getInt(path, def);
        } finally {
            exit();
        }
    }

    @Override
    public long getLong(final String path, final long def) {
        final boolean record = this.enter();
        try {
            if (record) {
                this.usage.record(path, ConfigurationUsage.READ_NUMBER);
            }
            return super.getLong(path, def);
        } finally {
            exit();
        }
    }

    @Override
    public double getDouble(final String path, final double def) {
        final boolean record = this.enter();
        try {
            if (record) {
                this.usage.record(path, ConfigurationUsage.READ_NUMBER);
            }
            return super.getDouble(path, def);
        } finally {
            exit();
        }
    }

    @Override
    public boolean getBoolean(final String path, final boolean def) {
        final boolean record = this.enter();
        try {
            if (record) {
                this.usage.record(path, ConfigurationUsage.READ_BOOLEAN);
            }
            return super.getBoolean(path, def);
        } finally {
            exit();
        }
    }

    @Override
    public @Nullable List<?> getList(final String path, final @Nullable List<?> def) {
        final boolean record = this.enter();
        try {
            if (record) {
                this.usage.record(path, ConfigurationUsage.READ_LIST);
            }
            return super.getList(path, def);
        } finally {
            exit();
        }
    }

    @Override
    public @Nullable ConfigurationSection getConfigurationSection(final String path) {
        final boolean record = this.enter();
        try {
            final ConfigurationSection section = super.getConfigurationSection(path);
            if (record) {
                this.usage.record(path, section != null ? ConfigurationUsage.READ_SECTION : ConfigurationUsage.PROBE);
            }
            return section;
        } finally {
            exit();
        }
    }

    @Override
    public void set(final String path, final @Nullable Object value) {
        final boolean record = this.enter();
        try {
            super.set(path, value);
            if (record && value != null) {
                final boolean section = value instanceof ConfigurationSection || value instanceof Map<?, ?>;
                this.usage.record(path, section ? ConfigurationUsage.WRITE_SECTION : ConfigurationUsage.WRITE);
            }
        } finally {
            exit();
        }
    }

    @Override
    public ConfigurationSection createSection(final String path) {
        final boolean record = this.enter();
        try {
            final ConfigurationSection section = super.createSection(path);
            if (record) {
                // The caller fills the returned section itself, which cannot be tracked
                this.usage.record(path, ConfigurationUsage.WRITE_SECTION);
            }
            return section;
        } finally {
            exit();
        }
    }

    @Override
    public ConfigurationSection createSection(final String path, final Map<?, ?> map) {
        final boolean record = this.enter();
        try {
            final ConfigurationSection section = super.createSection(path, map);
            if (record) {
                this.usage.record(path, ConfigurationUsage.WRITE_SECTION);
            }
            return section;
        } finally {
            exit();
        }
    }

    // Reading or writing comments is not a use of a setting, but resolves the path through getConfigurationSection

    @Override
    public List<String> getComments(final String path) {
        this.enter();
        try {
            return super.getComments(path);
        } finally {
            exit();
        }
    }

    @Override
    public List<String> getInlineComments(final String path) {
        this.enter();
        try {
            return super.getInlineComments(path);
        } finally {
            exit();
        }
    }

    @Override
    public void setComments(final String path, final @Nullable List<String> comments) {
        this.enter();
        try {
            super.setComments(path, comments);
        } finally {
            exit();
        }
    }

    @Override
    public void setInlineComments(final String path, final @Nullable List<String> comments) {
        this.enter();
        try {
            super.setInlineComments(path, comments);
        } finally {
            exit();
        }
    }

    @Override
    public void load(final File file) throws IOException, InvalidConfigurationException {
        this.file = file;
        super.load(file);
    }

    @Override
    public void loadFromString(final String contents) throws InvalidConfigurationException {
        // Loading builds the section tree through set() and createSection(); that is not usage by the server
        this.enter();
        try {
            super.loadFromString(contents);
        } finally {
            exit();
        }
    }
}
