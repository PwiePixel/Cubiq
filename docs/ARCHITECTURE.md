# Architecture

Cubiq is a Minecraft: Java Edition server built as a patch set on top of Purpur, which is itself a patch set on
top of Paper. This document explains how the server is assembled, how the code is organized, and where Cubiq's
own code connects to the inherited server.

## Layers

```
Minecraft 26.3 server (Mojang, downloaded at build time and by Paperclip at first start)
  └─ Paper        CraftBukkit, Spigot and Paper changes                 GPLv3 (partly MIT)
      └─ Purpur   gameplay options, purpur.yml, extra API               MIT patches on GPLv3 code
          └─ Cubiq  rebranding, diagnostics, future Cubiq features      Apache 2.0 contributions
```

Each layer only stores its *changes*. The pins that select the exact upstream code are:

| Layer | Pinned by | Current pin |
| --- | --- | --- |
| Purpur | `purpurCommit` in `gradle.properties` | `087d0657` (2026-10-24) |
| Paper | `paperCommit` in Purpur's `gradle.properties` at that commit | `e3ba7b1d` |
| Minecraft | `mcVersion` in `gradle.properties` | `26.3` |

## Build pipeline

The build uses [paperweight](https://github.com/PaperMC/paperweight) 2.0.0-beta.24 in its "patcher" mode, the same
mechanism Purpur uses to build on Paper. `./gradlew applyAllPatches` runs these steps:

1. **Upstream checkout.** Purpur is cloned at `purpurCommit`. paperweight runs Purpur's own patch application in
   that checkout, which clones Paper at Purpur's `paperCommit` and applies Purpur's changes to Paper's API and
   checkstyle rules.
2. **API and build scripts.** Cubiq's patch sets are applied on top of the result:
   - `paper-api/` = Paper's API + Purpur's API patches + `cubiq-api/paper-patches`
   - `purpur-api/` = Purpur's API classes + `cubiq-api/purpur-patches`
   - `cubiq-*/build.gradle.kts` = Purpur's build scripts + `cubiq-*/build.gradle.kts.patch`
3. **Minecraft sources.** The vanilla 26.3 server jar is downloaded from Mojang and turned into sources with Paper's
   [mache](https://github.com/PaperMC/mache) tooling. Then three layers of Minecraft patches are applied in order:
   Paper's (943 file patches at the current pin), Purpur's (230 file patches and 21 feature patches) and Cubiq's
   (`cubiq-server/minecraft-patches`). The result is `cubiq-server/src/minecraft/`.
4. **Server sources.** `paper-server/` = Paper's server code (CraftBukkit, Spigot and Paper classes) + Purpur's
   server patches + `cubiq-server/paper-patches`. `purpur-server/` = Purpur's own server classes +
   `cubiq-server/purpur-patches`.

Every generated directory is a Git repository whose history contains one commit per layer, which is what the
`fixup*`/`rebuild*` tasks use to turn edits back into patches (see [CONTRIBUTING.md](CONTRIBUTING.md)).

`./gradlew createPaperclipJar` compiles the API and the server and packages them:

- `cubiq-server-<version>.jar`: the compiled server classes, with Cubiq's manifest (`Brand-Id: cubiqstudio:cubiq`,
  `Brand-Name: Cubiq`, `Git-Commit`, ...) and the license files under `META-INF/license/`.
- `cubiq-bundler-<version>.jar`: the server with its libraries, in Mojang's bundler format.
- `cubiq-paperclip-<version>.jar`: the distributable jar. It contains binary patches against the vanilla server but
  not the vanilla server itself. On first start it downloads the vanilla jar from Mojang (URL and SHA-256 are pinned
  in `META-INF/download-context`), patches it, and starts `org.bukkit.craftbukkit.Main`.

## Modules and source sets

| Gradle project | Sources compiled |
| --- | --- |
| `:cubiq-api` | `paper-api/src/main/java`, `paper-api/src/generated/java`, `purpur-api/src/main/java`, `cubiq-api/src/main/java` |
| `:cubiq-server` | `paper-server/src/main/java`, `purpur-server/src/main/java`, `cubiq-server/src/minecraft/java`, `cubiq-server/src/main/java`, plus the `log4jPlugins` source set |
| `:cubiq-checkstyle` | Paper's custom checkstyle checks, applied to the API |

The server tests (`paper-server/src/test/java` and `cubiq-server/src/test/java`) run through JUnit platform
suites: the test task only executes classes named `*TestSuite`. Upstream's suites select the `org.bukkit`,
`io.papermc.paper` and `com.destroystokyo.paper` packages; Cubiq's suites in `studio.cubiq.testing` select
`studio.cubiq`.

## Cubiq's code

Cubiq's own classes live in `cubiq-server/src/main/java` under `studio.cubiq`:

| Package | Purpose |
| --- | --- |
| `studio.cubiq` | Project constants (`Cubiq.REPOSITORY_URL`, `Cubiq.ISSUES_URL`) and the `Cubiq` logger |
| `studio.cubiq.version` | `CubiqVersionFetcher`, the `/version` update status for Cubiq builds |
| `studio.cubiq.configcheck` | The configuration check, see [FEATURES.md](FEATURES.md) |
| `studio.cubiq.lifecycle` | Lifecycle diagnostics: the breakdowns of startup and shutdown and their JSON report, plugin timing and the lifecycle watchdog, see [FEATURES.md](FEATURES.md#lifecycle-diagnostics) |
| `studio.cubiq.watchdog` | Improvements to the inherited watchdog, see [FEATURES.md](FEATURES.md#watchdog-improvements) |

Changes to upstream code are kept as small as possible: a patch hook is usually one line that calls into a
`studio.cubiq` class and is marked with a `// Cubiq` comment. This keeps upstream updates cheap and makes every
change to inherited behavior easy to find. The current hooks are:

| Hook | Location | Purpose |
| --- | --- | --- |
| Brand fallback and compatibility | `paper-server: io.papermc.paper.ServerBuildInfoImpl` | Report Cubiq, stay compatible with Paper and Purpur brand checks |
| Version fetcher | `paper-server: io.papermc.paper.PaperServerInternalAPIBridge` | Use `CubiqVersionFetcher` |
| bStats | `paper-server: com.destroystokyo.paper.Metrics` | Do not submit server metrics under Purpur's name |
| Crash and watchdog text | `net.minecraft.CrashReport`, `org.spigotmc.WatchdogThread`, `CraftScheduler`, `PaperConsole` | Refer users to Cubiq |
| Watchdog hardening | `org.spigotmc.WatchdogThread` | Pause between all checks; CPU use and locks in the dumps (`studio.cubiq.watchdog`) |
| Configuration tracking | `org.spigotmc.SpigotConfig`, `org.purpurmc.purpur.PurpurConfig`, `org.bukkit.craftbukkit.CraftServer` | Create `TrackedYamlConfiguration`s |
| Configuration check | `net.minecraft.server.MinecraftServer` after the `Done` message | Run `ConfigurationCheck` |
| Paper configuration check | `paper-server: io.papermc.paper.configuration.Configurations` after each file is loaded | Compare the file with the settings Paper knows (`PaperConfigurationCheck`) |
| Paper's update of `paper-global.yml` | `paper-server: io.papermc.paper.configuration.transformation.global.versioned.V31_AllowNetherPropertiesToConfig` | Keep the other settings of `server.properties` when moving `allow-nether` out of it |
| `server.properties` check | `net.minecraft.server.dedicated.Settings` (`getStringRaw`, value conversion), `DedicatedServerSettings` after the file is loaded | Record which settings Minecraft reads and which values it cannot use as written (`PropertiesUsage`); compare them with the file (`ServerPropertiesCheck`) |
| Restart | `org.spigotmc.RestartCommand` | Watch the shutdown by `/restart` from the moment Paper's watchdog stops; time kicking the players and closing the network port |
| Stronghold searches at shutdown | `net.minecraft.world.level.chunk.ChunkGeneratorStructureState`, `MinecraftServer#stopServer` | End the searches for the positions of concentric ring structures that have not started yet, before waiting for Minecraft's background threads |
| Lifecycle phases | `net.minecraft.server.Main` (bootstrap), `MinecraftServer` (`runServer`, `Done`, `stopServer`), `CraftServer#reload` | Report startup, reload and shutdown to `ServerLifecycle` |
| Server steps | `net.minecraft.server.Main` (bootstrap, data packs), `DedicatedServer#initServer` (server setup, loading and enabling plugins, worlds, the initial save), `MinecraftServer` (enabling plugins after the worlds, delayed init tasks, the steps of `stopServer`, saving each world in `saveAllChunks`), `PaperWorldLoader` (loading and preparing each world) | Time the steps of startup and shutdown (`ServerStep`); give the watchdog progress and step names |
| Plugin steps | `PaperPluginInstanceManager` (enable, disable), `BootstrapProviderStorage`, `ServerPluginProviderStorage` (`onLoad`) | Time plugin steps; give the watchdog progress and step names |

## Inherited runtime architecture

Cubiq does not change the core runtime yet. The following summarizes the parts of the Paper/Purpur architecture
that Cubiq's roadmap builds on.

### Startup

`org.bukkit.craftbukkit.Main` parses the command line, then the Minecraft bootstrap (`net.minecraft.server.Main`)
loads `server.properties`, writes it back right away and starts the dedicated server.
`DedicatedServer#initServer` creates the player list and with it `CraftServer` (which
loads `bukkit.yml`), initializes Spigot's, Paper's and Purpur's configurations, loads all plugins and enables those
with `load: STARTUP`, then loads the worlds (`MinecraftServer#loadLevel`). `MinecraftServer#initPostWorld` enables the
remaining (`POSTWORLD`) plugins once the worlds exist, and the server finally logs `Done (<seconds>)!`. Cubiq's configuration check runs right after
that message, when every world's configuration has been read.

### Main thread and ticking

The game state (worlds, entities, block updates, scheduled ticks) is owned by a single server thread that runs
the tick loop at 20 ticks per second. Bukkit's scheduler (`CraftScheduler`) runs synchronous tasks on that thread
once per tick and asynchronous tasks on a thread pool. Paper detects many accesses to world state from other
threads (`TickThread` and `AsyncCatcher` checks), logs them with a stack trace and rejects them with an
`IllegalStateException` instead of corrupting state.

Some work runs on other threads by design, and plugins see it through events: chat is processed on a chat thread
pool (`AsyncChatEvent`), logins are authenticated on virtual threads (`AsyncPlayerPreLoginEvent`), and Paper has
further pools for tab completion, command tree building and file I/O.

Paper includes the Moonrise chunk system, which loads, generates and saves chunks with worker threads while the
results are still applied by the server thread. Paper also implements Folia's scheduler API (region, entity,
global and async schedulers) so that plugins written for Folia can run on Paper; on Paper and therefore on Cubiq,
region and entity tasks run on the main thread.

### Threading rules for Cubiq code

Until Cubiq introduces region-aware execution (see [ROADMAP.md](ROADMAP.md)), Cubiq code follows Paper's rules:

- World, chunk, entity and player state may only be read or changed on the server thread, unless the specific API
  documents otherwise.
- Work that may block (file or network I/O) must not run on the server thread during ticking.
- Diagnostics must never break the server: Cubiq's checks catch and log their own failures.

Any future concurrency feature must first document its ownership rules here, including which thread owns which
state and how work moves between owners.

### Thread-safety contract of the lifecycle diagnostics

- A lifecycle phase belongs to the thread that opened it: the main thread while the server bootstraps, the server
  thread during startup, `/reload` and a normal shutdown, and the `Server Shutdown Thread` when a stuck server is
  stopped by a signal. Phases are normally closed by that thread with try-with-resources; closing a phase also
  closes the phases opened inside it.
- `LifecycleMonitor` keeps one entry per thread. All of an entry's state is guarded by the entry's lock, and no
  other lock is taken while it is held. Listeners and logging run after the lock has been released.
- The watchdog runs on its own daemon thread (`Cubiq Lifecycle Watchdog`). It only reads snapshots of the entries,
  never changes server state, and drops the entries of threads that have terminated.
- `LifecycleController` changes the server's lifecycle state atomically. It reports only in the states in which
  Paper's watchdog is not armed (starting, reloading, stopping).
- The breakdowns of startup and shutdown (`Breakdown`) are filled by the threads that close the steps, under the
  breakdown's own lock; the controller decides by the state whether a step belongs to the startup, the shutdown or
  neither. The JSON report is written by the thread that ends the startup or the shutdown.
