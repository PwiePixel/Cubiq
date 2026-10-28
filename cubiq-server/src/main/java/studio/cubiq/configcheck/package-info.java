/**
 * Detects settings in the server's YAML configuration files that have no effect.
 *
 * <p>{@link studio.cubiq.configcheck.TrackedYamlConfiguration} records which settings the server uses while it
 * starts, and {@link studio.cubiq.configcheck.ConfigurationCheck} compares them with the files once startup has
 * finished.</p>
 */
@NullMarked
package studio.cubiq.configcheck;

import org.jspecify.annotations.NullMarked;
