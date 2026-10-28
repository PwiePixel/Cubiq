package studio.cubiq.configcheck;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Compares the settings in a configuration file with the settings the server uses.
 *
 * <p>A setting is considered used when the server read or wrote its path, or when the configuration declares a
 * default for it. Settings below a section that the server consumed as a whole (for example a map of entity
 * types) may have any name. Everything else in the file has no effect and is reported, together with the most
 * similar used setting when there is one. Settings that are used but have a value of the wrong type are reported
 * too, because Bukkit's typed getters silently fall back to the default for them.</p>
 */
@NullMarked
final class ConfigurationChecker {

    private static final int MAX_VALUE_LENGTH = 40;

    /**
     * A section whose children are named scopes, such as worlds, that all accept the same settings. The settings of
     * every scope are checked against those of the canonical scope, so {@code world-settings.my_world.x} is valid
     * when {@code world-settings.default.x} is used.
     *
     * @param container     the path of the section containing the scopes
     * @param canonicalName the name of the scope that defines the available settings
     */
    record Scope(String container, String canonicalName) {
    }

    private final String file;
    private final @Nullable Scope scope;
    /** Canonical paths used by the server. */
    private final Set<String> used = new HashSet<>();
    /** Canonical proper prefixes of used paths, i.e. sections that contain used settings. */
    private final Set<String> usedSections = new HashSet<>();
    /** Canonical paths of sections whose children may have any name. */
    private final Set<String> freeForm = new HashSet<>();
    /** Merged access flags per canonical path. */
    private final Map<String, Integer> access = new HashMap<>();
    /** The read flag matching the type of each default value, per canonical path. */
    private final Map<String, Integer> defaultTypes = new HashMap<>();
    /** Names of the used children of each canonical section, for suggestions. */
    private final Map<String, Set<String>> childrenByParent = new HashMap<>();
    /** Canonical paths of used settings by their normalized last path element, for suggesting moved settings. */
    private final Map<String, List<String>> settingsByName = new HashMap<>();

    /**
     * @param file     the configuration file, used in findings
     * @param usage    the access flags recorded per path, see {@link ConfigurationUsage}
     * @param defaults the defaults of the configuration, if any
     * @param scope    the scoped section of this configuration, if any
     */
    ConfigurationChecker(final String file, final Map<String, Integer> usage, final @Nullable Configuration defaults, final @Nullable Scope scope) {
        this.file = file;
        this.scope = scope;
        for (final Map.Entry<String, Integer> entry : usage.entrySet()) {
            final String path = this.canonical(entry.getKey());
            final int flags = entry.getValue();
            this.access.merge(path, flags, (previous, added) -> previous | added);
            this.markUsed(path);
            if ((flags & ConfigurationUsage.SECTION_ACCESS) != 0 && !this.isScopeRoot(path)) {
                this.freeForm.add(path);
            }
        }
        if (defaults != null) {
            for (final String path : defaults.getKeys(true)) {
                final String canonical = this.canonical(path);
                this.markUsed(canonical);
                final Object value = defaults.get(path);
                if (value instanceof Map<?, ?> && !this.isScopeRoot(canonical)) {
                    // A map registered as a default is stored as a single value and consumed as a whole
                    this.freeForm.add(canonical);
                } else if (!(value instanceof ConfigurationSection)) {
                    this.defaultTypes.merge(canonical, ConfigurationUsage.typeOf(value), (previous, added) -> previous | added);
                }
            }
        }
        for (final String path : this.used) {
            this.childrenByParent.computeIfAbsent(parent(path), key -> new TreeSet<>()).add(lastElement(path));
            if (!this.usedSections.contains(path)) {
                this.settingsByName.computeIfAbsent(KeySimilarity.normalize(lastElement(path)), key -> new ArrayList<>()).add(path);
            }
        }
        for (final String section : this.usedSections) {
            this.childrenByParent.computeIfAbsent(parent(section), key -> new TreeSet<>()).add(lastElement(section));
        }
    }

    private void markUsed(final String path) {
        if (this.used.add(path)) {
            for (int i = path.indexOf('.'); i > 0; i = path.indexOf('.', i + 1)) {
                this.usedSections.add(path.substring(0, i));
            }
        }
    }

    /**
     * Checks a configuration file.
     *
     * @param contents the file as loaded from disk, without defaults
     * @return the problems found, in the order they appear in the file
     */
    List<ConfigurationFinding> check(final ConfigurationSection contents) {
        final Map<String, Object> results = new LinkedHashMap<>();
        for (final String path : contents.getKeys(true)) {
            final Object value = contents.get(path);
            if (value instanceof ConfigurationSection section && !section.getKeys(false).isEmpty()) {
                continue; // its settings are checked individually
            }
            final String canonical = this.canonical(path);
            if (this.isInFreeFormSection(canonical)) {
                continue;
            }
            if (this.used.contains(canonical)) {
                final ConfigurationFinding invalid = this.checkValue(path, canonical, value);
                if (invalid != null) {
                    results.put(path, invalid);
                }
                continue;
            }
            if (this.usedSections.contains(canonical)) {
                continue; // a used section that is empty or has an unexpected value
            }
            if (this.isUsedInScopeWithDots(path)) {
                continue;
            }
            final String unknownRoot = this.topmostUnknown(path);
            final UnknownGroup group = (UnknownGroup) results.computeIfAbsent(unknownRoot, key -> new UnknownGroup(!key.equals(path)));
            group.settings++;
        }

        final List<ConfigurationFinding> findings = new ArrayList<>(results.size());
        for (final Map.Entry<String, Object> result : results.entrySet()) {
            if (result.getValue() instanceof ConfigurationFinding finding) {
                findings.add(finding);
            } else {
                final UnknownGroup group = (UnknownGroup) result.getValue();
                final String path = result.getKey();
                final String suggestion = this.suggest(path, group.section);
                findings.add(group.section
                    ? ConfigurationFinding.unknownSection(this.file, path, group.settings, suggestion)
                    : ConfigurationFinding.unknownSetting(this.file, path, suggestion));
            }
        }
        return findings;
    }

    private @Nullable ConfigurationFinding checkValue(final String path, final String canonical, final @Nullable Object value) {
        int flags = this.access.getOrDefault(canonical, 0);
        if ((flags & ConfigurationUsage.READS) == 0) {
            // Not read while the server started, for example because it is only read when a player joins:
            // the type of its default value is the best indication of the type the server expects
            flags = this.defaultTypes.getOrDefault(canonical, 0);
        }
        if ((flags & ConfigurationUsage.READ_TEXT) != 0) {
            return null; // read as text somewhere, which accepts any value
        }
        final int typedReads = flags & ConfigurationUsage.TYPED_READS;
        if (Integer.bitCount(typedReads) != 1) {
            return null; // not read with a type, or read with conflicting types
        }
        return switch (typedReads) {
            case ConfigurationUsage.READ_BOOLEAN -> value instanceof Boolean ? null
                : ConfigurationFinding.invalidValue(this.file, path, "true or false", describe(value));
            case ConfigurationUsage.READ_NUMBER -> value instanceof Number ? null
                : ConfigurationFinding.invalidValue(this.file, path, "a number", describe(value));
            case ConfigurationUsage.READ_LIST -> value instanceof List<?> ? null
                : ConfigurationFinding.invalidValue(this.file, path, "a list", describe(value));
            default -> null;
        };
    }

    /**
     * Finds the outermost section of {@code path} that the server does not use, or the path itself.
     */
    private String topmostUnknown(final String path) {
        for (int i = path.indexOf('.'); i > 0; i = path.indexOf('.', i + 1)) {
            final String prefix = path.substring(0, i);
            final String canonical = this.canonical(prefix);
            if (!this.used.contains(canonical) && !this.usedSections.contains(canonical)) {
                return prefix;
            }
        }
        return path;
    }

    /**
     * Suggests the used setting or section that was most likely meant, written in the scope of {@code path}.
     */
    private @Nullable String suggest(final String path, final boolean section) {
        final String canonical = this.canonical(path);
        final String parent = parent(canonical);
        final String name = lastElement(canonical);

        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (final String sibling : this.childrenByParent.getOrDefault(parent, Set.of())) {
            final int distance = KeySimilarity.similarity(name, sibling);
            if (distance >= 0 && distance < bestDistance) { // siblings are sorted, so ties keep the first name
                best = sibling;
                bestDistance = distance;
            }
        }
        if (best != null) {
            return this.inScopeOf(parent.isEmpty() ? best : parent + '.' + best, path);
        }

        if (!section) {
            // The setting may have been put into the wrong section; suggest it when its name is unambiguous
            final List<String> sameName = this.settingsByName.getOrDefault(KeySimilarity.normalize(name), List.of());
            if (sameName.size() == 1) {
                return this.inScopeOf(sameName.getFirst(), path);
            }
        }
        return null;
    }

    private boolean isInFreeFormSection(final String canonical) {
        for (int i = canonical.lastIndexOf('.'); i > 0; i = canonical.lastIndexOf('.', i - 1)) {
            if (this.freeForm.contains(canonical.substring(0, i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scope names can contain the path separator, for example the world key {@code minecraft:event.2026}, which
     * {@link #canonical(String)} cannot tell apart from a setting. Checks whether the path is used when the scope
     * name extends over more path elements.
     */
    private boolean isUsedInScopeWithDots(final String path) {
        if (this.scope == null || !path.startsWith(this.scope.container() + '.')) {
            return false;
        }
        int scopeEnd = path.indexOf('.', this.scope.container().length() + 1);
        while (scopeEnd >= 0) {
            scopeEnd = path.indexOf('.', scopeEnd + 1);
            if (scopeEnd < 0) {
                break;
            }
            final String candidate = this.scope.container() + '.' + this.scope.canonicalName() + path.substring(scopeEnd);
            if (this.used.contains(candidate) || this.usedSections.contains(candidate) || this.isInFreeFormSection(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean isScopeRoot(final String canonical) {
        return this.scope != null
            && (canonical.equals(this.scope.container()) || canonical.equals(this.scope.container() + '.' + this.scope.canonicalName()));
    }

    /**
     * Maps a path in any scope to the same path in the canonical scope.
     */
    String canonical(final String path) {
        if (this.scope == null) {
            return path;
        }
        final String prefix = this.scope.container() + '.';
        if (!path.startsWith(prefix)) {
            return path;
        }
        final int scopeEnd = path.indexOf('.', prefix.length());
        return prefix + this.scope.canonicalName() + (scopeEnd < 0 ? "" : path.substring(scopeEnd));
    }

    /**
     * Rewrites a canonical path into the scope that {@code original} is in.
     */
    private String inScopeOf(final String canonical, final String original) {
        if (this.scope == null) {
            return canonical;
        }
        final String prefix = this.scope.container() + '.';
        final String canonicalScope = prefix + this.scope.canonicalName();
        if (!original.startsWith(prefix) || !(canonical.equals(canonicalScope) || canonical.startsWith(canonicalScope + '.'))) {
            return canonical;
        }
        final int scopeEnd = original.indexOf('.', prefix.length());
        final String originalScope = scopeEnd < 0 ? original : original.substring(0, scopeEnd);
        return originalScope + canonical.substring(canonicalScope.length());
    }

    private static String parent(final String path) {
        final int index = path.lastIndexOf('.');
        return index < 0 ? "" : path.substring(0, index);
    }

    private static String lastElement(final String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    static String describe(final @Nullable Object value) {
        if (value instanceof String text) {
            return '"' + abbreviate(text) + '"';
        }
        if (value instanceof List<?>) {
            return "a list";
        }
        if (value instanceof ConfigurationSection) {
            return "a section";
        }
        return abbreviate(String.valueOf(value));
    }

    private static String abbreviate(final String text) {
        return text.length() <= MAX_VALUE_LENGTH ? text : text.substring(0, MAX_VALUE_LENGTH - 3) + "...";
    }

    private static final class UnknownGroup {
        private final boolean section;
        private int settings;

        private UnknownGroup(final boolean section) {
            this.section = section;
        }
    }
}
