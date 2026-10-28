package studio.cubiq.lifecycle;

import org.jspecify.annotations.Nullable;

/**
 * A step of starting or stopping the server that the breakdowns of startup and shutdown report.
 *
 * <p>The breakdowns add up the steps with the same label. A step with a subject, such as the name of a world, that
 * runs inside a step with the same label is listed as a detail of that step; inside a step with another label, its
 * time is only part of the enclosing step.</p>
 */
public enum ServerStep {
    /** Discovering the plugins, running the bootstrappers of Paper plugins and bootstrapping Minecraft. */
    BOOTSTRAPPING("Bootstrapping the server", "bootstrapping"),
    /** Loading the data packs and registries, before the server is created. */
    LOADING_DATA_PACKS("Loading data packs", "data packs"),
    /** Loading the configuration files, setting up the player lists and commands, and opening the network port. */
    SETTING_UP_THE_SERVER("Setting up the server", "server setup"),
    /** Loading the plugins ({@code Plugin#onLoad()}). */
    LOADING_PLUGINS("Loading plugins", "loading plugins"),
    /** Enabling the plugins, before the worlds are loaded or after. */
    ENABLING_PLUGINS("Enabling plugins", "enabling plugins"),
    /** Loading the worlds that the server starts with and preparing their spawn areas. */
    LOADING_WORLDS("Loading worlds", "worlds"),
    /** Loading one world; the subject is its name. */
    LOADING_WORLD("Loading world", "worlds"),
    /** Preparing the spawn area of one world; the subject is its name. */
    PREPARING_WORLD("Preparing world", "worlds"),
    /** Running the tasks scheduled for the first tick, which the server runs before it reports that it has started. */
    RUNNING_DELAYED_INIT_TASKS("Running delayed init tasks", "delayed init tasks"),
    /** Disabling the plugins. */
    DISABLING_PLUGINS("Disabling plugins", "disabling plugins"),
    /** Waiting for the asynchronous tasks of the plugins to finish. */
    WAITING_FOR_PLUGIN_TASKS("Waiting for asynchronous tasks of plugins", "plugin tasks"),
    /** Saving the players and removing them from the server. */
    SAVING_PLAYERS("Saving players", "players"),
    /** Saving the worlds. */
    SAVING_WORLDS("Saving worlds", "worlds"),
    /** Saving one world; the subject is its name. */
    SAVING_WORLD("Saving world", "worlds"),
    /** Waiting for the region files to be written. */
    WAITING_FOR_REGION_FILES("Waiting for region file I/O", "region files");

    private final String description;
    private final String label;

    ServerStep(final String description, final String label) {
        this.description = description;
        this.label = label;
    }

    /**
     * Describes this step, for example {@code Loading world world_nether}.
     *
     * @param subject what the step works on, for example the name of a world, or {@code null}
     * @return the description
     */
    String describe(final @Nullable String subject) {
        return subject == null ? this.description : this.description + " " + subject;
    }

    /**
     * Returns the name of this step in the breakdowns, for example {@code worlds}.
     *
     * @return the name
     */
    String label() {
        return this.label;
    }
}
