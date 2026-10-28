/**
 * Detects settings in the server's configuration files that do not work as written.
 *
 * <p>{@link studio.cubiq.configcheck.TrackedYamlConfiguration} records which settings the server uses while it
 * starts, and {@link studio.cubiq.configcheck.ConfigurationCheck} compares them with the files once startup has
 * finished. {@link studio.cubiq.configcheck.PaperConfigurationCheck} checks Paper's configuration files when Paper
 * loads them, and {@link studio.cubiq.configcheck.ServerPropertiesCheck} checks {@code server.properties} when
 * Minecraft loads it, using what {@link studio.cubiq.configcheck.PropertiesUsage} recorded.</p>
 */
@NullMarked
package studio.cubiq.configcheck;

import org.jspecify.annotations.NullMarked;
