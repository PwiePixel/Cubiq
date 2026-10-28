# Cubiq features

This page describes the features Cubiq adds on top of Purpur and Paper. Everything else behaves as in
Purpur; see [COMPATIBILITY.md](COMPATIBILITY.md).

## Configuration check

CraftBukkit, Spigot and Purpur silently ignore settings they do not know and settings whose value has the
wrong type. Paper keeps settings it does not know in its configuration files and ignores them as well. Minecraft
does the same with `server.properties`: it keeps settings it does not read in the file, uses the default value
instead of most values it cannot read, reads every value of a `true`/`false` setting other than `true` (in any case) as
`false`, and writes the values it uses back to the file. A misspelled setting therefore has no effect, and nothing
tells the server owner about it.

Once the server has started, Cubiq compares `server.properties` with the settings Minecraft read and the values it
could use, `bukkit.yml`, `spigot.yml` and `purpur.yml` with the settings the server actually used while starting,
and Paper's configuration files with the settings Paper knows, and logs every setting that does not work as written
as a warning of the `Cubiq` logger, for example:

```
Configuration check: found 10 problems in server.properties, bukkit.yml, purpur.yml, spigot.yml, config/paper-global.yml, config/paper-world-defaults.yml, world/dimensions/minecraft/overworld/paper-world.yml, world/dimensions/minecraft/the_nether/paper-world.yml, world/dimensions/minecraft/the_end/paper-world.yml (71 ms). The following settings do not work as written:
  server.properties: Unknown setting 'max-player' has no effect. Did you mean 'max-players'?
  server.properties: Setting 'pvp' has no effect anymore. Use the game rule pvp instead.
  server.properties: 'view-distance' must be a whole number, but is "twelve", so the server uses the default value 10.
  server.properties: 'white-list' must be true or false, but is "true " (with whitespace at the end), so the server uses false.
  bukkit.yml: Unknown setting 'spawn-limits.monster' has no effect. Did you mean 'spawn-limits.monsters'?
  purpur.yml: Unknown setting 'settings.use-alternate-keepaliv' has no effect. Did you mean 'settings.use-alternate-keepalive'?
  spigot.yml: 'world-settings.default.mob-spawn-range' must be a number, but is "eight", so it is ignored.
  config/paper-global.yml: Unknown setting 'chunk-loading-basic.player-max-chunk-load-rat' has no effect. Did you mean 'chunk-loading-basic.player-max-chunk-load-rate'?
  config/paper-world-defaults.yml: Unknown setting 'chunks.auto-save-intervall' has no effect. Did you mean 'chunks.auto-save-interval'?
  world/dimensions/minecraft/overworld/paper-world.yml: Unknown setting 'entities.spawning.spawn-limits.monsters' has no effect. Did you mean 'entities.spawning.spawn-limits.monster'?
Fix or remove these settings. This check runs on every start and can be disabled with -Dcubiq.configCheck=false
```

When nothing is wrong, a single informational line confirms which files were checked. Both messages include how
long the check took.

What is reported:

- **Unknown settings and sections**: settings that the server neither read nor wrote while starting and that have
  no default. A section that is unknown as a whole is reported once, with the number of settings in it.
- **Suggestions**: the most similar setting at the same place in the file, ignoring case and `-`/`_`
  differences, or the same setting in another section when it was put in the wrong place.
- **Values of the wrong type**: for example text where a number or `true`/`false` is expected. The server uses the
  default value instead of such a value. The expected type is taken from how the server read the setting, or, for
  settings that are only read later (for example when a player joins), from the type of their default value.
- **Settings of `server.properties` that were replaced**, with what to use instead: `pvp` (the game rule `pvp`),
  `spawn-monsters` (the game rule `spawn_monsters`), `enable-command-block` (the game rule `command_blocks_work`) and
  `allow-nether` (`misc.enable-nether` in Paper's `paper-global.yml`). Game rules are set per world with `/gamerule`.
- **Values of `server.properties` that Minecraft does not use as written**, with the value it uses instead: values it
  cannot convert, for which it uses the default value; values of `true`/`false` settings other than `true` or
  `false` in any case, which it reads as `false`, for example `true ` with a space at the end, so that
  `white-list=true ` turns the whitelist off; and game modes it does not know, for which it uses `survival`. This
  includes the legacy setting `announce-player-achievements`, which Minecraft moves to a game rule. A value is not
  reported when the server uses what it means, for example the default `easy` for `difficulty=Easy`, which Minecraft
  cannot read because the names are case-sensitive. Minecraft writes the value it uses to the file right away, so
  these findings are only reported on the start after the file was edited, unless the file is read-only. For
  `entity-broadcast-range-percentage` and `generator-settings`, Minecraft does not use the default but fails to start
  with an error.
- **Invisible characters**: a byte order mark, which some editors put at the start of a file, becomes part of the name
  of the first setting of `server.properties`, so Minecraft does not read that setting. Such characters, and
  characters such as a no-break space, are shown as `\u` escapes, for example `\ufeffmotd`.

How it works:

- While the server starts, the configuration objects of `bukkit.yml`, `spigot.yml` and `purpur.yml` record which
  settings are read, with which expected type, and which settings the server writes. Recording stops when the check
  runs.
- Paper maps its files (`config/paper-global.yml`, `config/paper-world-defaults.yml` and the `paper-world.yml` of
  each world) to configuration objects. When Paper has loaded one of them, Cubiq writes the loaded configuration to
  an empty tree with Paper's own serializers, which gives exactly the settings Paper knows, including the entries of
  maps that Paper could read, and reports the settings of the file that are not in that tree. Entries of maps of
  registry entries match with and without the `minecraft:` namespace. A world file only contains the settings that
  the world overrides; it is checked without the defaults. Paper reports values of the wrong type in its files
  itself, because it cannot load such a file.
- Minecraft reads every setting of `server.properties` through one method. Cubiq records which settings Minecraft
  reads and checks each value that Minecraft converts against the text in the file, without changing either. When
  Minecraft has loaded the file, the settings in it that Minecraft did not read are reported, together with the values
  that it did not use as written.
- When Paper updates `paper-global.yml` from a version before 31 and `server.properties` contains `allow-nether`, it
  moves the value to `misc.enable-nether` and removes `allow-nether` from `server.properties`, so the check does not
  report it. Paper writes the file with only the settings that Minecraft writes back, which lost all other settings,
  including `rcon.ip`; Cubiq keeps them (see [COMPATIBILITY.md](COMPATIBILITY.md)). Settings that are removed from the
  file while the server starts all the same, such as legacy settings that Minecraft moves elsewhere, are reported with
  the note that the server removed them.
- Settings in a world section (`world-settings.<world>.<setting>`) are checked against the settings of
  `world-settings.default`, so settings for worlds that are not loaded are checked as well.
- Sections that the server reads as a whole, such as maps of blocks or entity types, may contain any key.
- The check only reads the files and logs; it never changes them. It runs once per start, after the
  `Done` message, and a failure inside the check is logged without affecting the server.

Limitations:

- `commands.yml` is not checked yet.
- In `server.properties`, values that Minecraft limits to a range, such as `max-world-size`, and numeric ids of
  difficulties and game modes outside their range are not reported.
- If `server.properties` is read-only and Paper updates `paper-global.yml` from an older version, Paper cannot remove
  `allow-nether` from the file, and the check reports it on that start although Paper moved its value.
- The `paper-world.yml` of a world that a plugin loads after startup is not checked, because the check has already
  run when Paper loads it.
- Plugins that read these files with their own code can make the check report settings that only those plugins use.
- Settings inside sections that the server reads as a whole, such as Purpur's maps of blocks, are not checked
  individually, because the server reads them through the section. The per-world `generator` and
  `biome-provider` settings below `worlds.<world>` in `bukkit.yml` are an exception and are checked.
- Settings that the server only reads later, for example after a `/reload`, are not known when the check runs.
  CraftBukkit's settings are covered by the defaults of `bukkit.yml`, so this mainly affects plugins.

Disable the check by starting the server with `-Dcubiq.configCheck=false`.

## Lifecycle diagnostics

Paper's watchdog only watches the ticks of a server that is running. It is not armed while the server starts,
while `/reload` runs and while the server stops, so a plugin or a step that hangs in these phases leaves the server
stuck without any explanation. No upstream server shows either which steps of starting and stopping take how long, or
how long each plugin takes to start and stop.

### Summaries

When the server has started, and again when it has stopped, Cubiq logs which steps took how long, and how long the
plugins took. For example, with the test plugin of Cubiq's smoke test, whose steps each take three seconds:

```
Startup took 24.9 s: bootstrapping 10.5 s, data packs 2.6 s, server setup 1.4 s, loading plugins 3.0 s, enabling plugins 3.0 s, worlds 922 ms (world 641 ms, world_nether 150 ms, world_the_end 114 ms), initial save 60 ms, delayed init tasks 1 ms, other 3.3 s.
Plugins took 9.0 s to start (bootstrap 3.0 s, load 3.0 s, enable 3.0 s). Plugin: LifecycleSmokeTest 9.0 s.
Stopped the server in 3.2 s: disabling plugins 3.0 s, worlds 20 ms (world 9 ms, world_nether 3 ms, world_the_end 3 ms), other 212 ms. Plugins took 3.0 s to stop. Plugin: LifecycleSmokeTest 3.0 s.
```

The startup time is the one of Paper's `Done` message, measured from the start of the server's main class. Its steps:

| Step | What it includes |
| --- | --- |
| bootstrapping | Discovering the plugins, running the bootstrappers of Paper plugins, and Minecraft's bootstrap (registries) |
| data packs | Loading the data packs and the registries that depend on them. When Paper imports a world that it has not run before and that Minecraft does not need to upgrade, for example a world of a vanilla 26.x server, also that import and the 30 seconds that Paper waits before it (`-Dpaper.disableMigrationDelay=true` skips the wait) |
| server setup | Loading the configuration files, the player lists and the commands, and opening the network port (including UPnP) |
| loading plugins | Creating the plugins (loading their classes, running their constructors and, on first start, downloading the libraries that Spigot plugins declare) and their `onLoad` |
| enabling plugins | Enabling the plugins, before and after the worlds are loaded |
| worlds | Loading the worlds and preparing their spawn areas, per world. Worlds that plugins create while they are enabled count for enabling plugins |
| initial save | Saving the players and all worlds once the worlds are loaded and the plugins enabled |
| delayed init tasks | The tasks that plugins scheduled for the first tick, which Paper runs before it reports `Done` |
| other | Everything else: starting the JVM's logging, reading `server.properties`, `eula.txt` and the world's `level.dat`, creating the server, starting the bundled spark profiler, Query and RCON, the plugins' listeners of Paper's command registration (the `COMMANDS` lifecycle event) and of `ServerLoadEvent`, the short pauses between the steps and, when Minecraft upgrades a world from before 26.1, that upgrade of the world's files and the 30 seconds that Paper waits before it |

The shutdown is measured from the moment the server begins to stop. Its steps are disabling the plugins, waiting for
their asynchronous tasks (`plugin tasks`), saving the players, saving the worlds (per world) and waiting for the
region files to be written; `other` is the rest. The lines leave out steps that took less than half a millisecond,
which would show as 0 ms.

With more plugins, the line names the five slowest ones. The time of a plugin is the sum of its bootstrap (Paper
plugins only), `onLoad`, enabling (`onEnable` and the `PluginEnableEvent`) and disabling (`onDisable` and removing
the plugin's tasks, services and listeners). Time that a plugin spends enabling or disabling another plugin counts
for the other plugin. The shutdown line only counts the plugins that the shutdown disabled, not a reload or a plugin
manager that was still busy when the server began to stop. Without plugins, the lines about plugins are left out.

### Report for tools

With `-Dcubiq.lifecycleReport=<file>`, Cubiq also writes these times to a JSON file when the server has started and
again when it has stopped, for example for benchmarks or monitoring. Times are in milliseconds with three decimals,
and steps that took no time are included. The report of a server without plugins, abridged:

```json
{
  "formatVersion": 1,
  "startup": {
    "totalMillis": 16398.0,
    "steps": [
      {"name": "bootstrapping", "millis": 6679.35},
      {"name": "data packs", "millis": 2828.16},
      {"name": "server setup", "millis": 1876.296},
      {"name": "loading plugins", "millis": 0.453},
      {"name": "enabling plugins", "millis": 34.627},
      {"name": "worlds", "millis": 955.43, "details": [
        {"name": "world", "millis": 740.204},
        {"name": "world_nether", "millis": 89.413},
        {"name": "world_the_end", "millis": 110.214}
      ]},
      {"name": "initial save", "millis": 83.315},
      {"name": "delayed init tasks", "millis": 17.137},
      {"name": "other", "millis": 3923.233}
    ],
    "plugins": []
  },
  "shutdown": {
    "totalMillis": 776.12,
    "steps": [
      {"name": "disabling plugins", "millis": 0.041},
      {"name": "worlds", "millis": 29.365, "details": [{"name": "world", "millis": 12.557}]},
      {"name": "other", "millis": 746.455}
    ],
    "plugins": []
  }
}
```

Together with `other`, the steps add up to `totalMillis` (see the limitations below). Each plugin is listed with the
time of its steps, for example `{"name": "Example", "loadMillis": 12.5, "enableMillis": 310.25}`. The file is replaced
atomically where the file system supports it, so a tool never reads half of it; a symbolic link at its path is replaced
as well, by the file. `formatVersion` changes when a field changes its meaning or is removed; new fields can be added
without a new version. A startup that failed has no `startup` part, and a server that is still running has no
`shutdown` part: when the server starts, Cubiq replaces the file with a report that has neither, so that a run that
fails before the server has started does not leave the report of an earlier run in place. The `startup` part is
written right after Paper's `Done` message, so a tool that waits for that message should then wait for the `startup`
part as well. If the file cannot be written, or its path is not valid, Cubiq logs a warning and the server is not
affected.

### Lifecycle watchdog

While the server starts, reloads or stops, Cubiq watches each step: the steps of the summaries above, and
bootstrapping, loading, enabling and disabling each plugin, loading, preparing and saving each world.
A shutdown by `/restart` is watched from the moment it stops Paper's watchdog, including the kick events that
plugins handle. When a thread has neither finished its current step nor started or finished another one for 30
seconds, Cubiq logs the step and the stack trace of that thread, including the monitors it holds and the lock it
waits for. If the thread waits for a lock that another thread holds, the stack trace of that thread follows. Stack
frames of plugin code start with the name of the plugin's jar. An abridged example:

```
Lifecycle watchdog: Enabling plugin LifecycleSmokeTest v1.0 has not finished after 30 s.
The thread that runs it is waiting, possibly for work on another thread, for example a plugin saving its data, so the step is not necessarily stuck if this message repeats with the same stack trace. Stack trace of thread "Server thread":
"Server thread" #51 TIMED_WAITING
	at java.base@25.0.5/java.lang.Thread.sleep(Thread.java:540)
	at LifecycleSmokeTest.jar//studio.cubiq.smoketest.StepDelay.waitInStep(StepDelay.java:19)
	at LifecycleSmokeTest.jar//studio.cubiq.smoketest.LifecycleSmokeTestPlugin.onEnable(LifecycleSmokeTestPlugin.java:17)
	at org.bukkit.plugin.java.JavaPlugin.setEnabled(JavaPlugin.java:280)
	...
```

As long as the step does not finish, the report is repeated every 60 seconds with a new stack trace. For a thread
that runs code, a stack trace that stays the same means that the step is probably stuck; the report says so. A
thread that waits, as in the example, may wait for work on another thread that still progresses. When a reported
step finishes after all, Cubiq logs how long it took, for example
`Lifecycle watchdog: Enabling plugin LifecycleSmokeTest v1.0 finished after 45.2 s.` The watchdog only reports: it
never stops, restarts or interrupts anything. While the server runs normally, Paper's watchdog is responsible and
Cubiq's watchdog reports nothing.

### Settings

| System property | Default | Meaning |
| --- | --- | --- |
| `cubiq.lifecycleWatchdog` | `true` | `false` disables the lifecycle watchdog. Paper's `-Ddisable.watchdog=true` disables it as well. |
| `cubiq.lifecycleWatchdog.warnAfter` | `30` | Seconds without progress before a step is reported (at least 1). |
| `cubiq.lifecycleWatchdog.warnEvery` | `60` | Seconds between reports while a step does not finish (at least 1). |
| `cubiq.lifecycleSummary` | `true` | `false` disables the startup and shutdown summaries. |
| `cubiq.lifecycleReport` | none | A file, for example `lifecycle-report.json`, to write the [report](#report-for-tools) to. A relative path is relative to the server directory. |

Set them on the command line, for example `java -Dcubiq.lifecycleWatchdog.warnAfter=60 -jar cubiq.jar`.

### Limitations

- Slow but healthy work can be reported, for example generating a large world, a plugin that migrates a database
  on its first start, or saving many chunks on shutdown. Repeated stack traces show whether the step progresses,
  unless the thread waits for another thread.
- The `java.util.concurrent` locks that a thread holds are not listed, because finding them pauses all threads for
  a scan of the whole heap. The lock that a thread waits for and the thread that holds it are shown.
- Work between the steps listed above, for example creating the server or closing the network connections on
  shutdown, belongs to the enclosing phase (`Starting the server`, `Reloading the server` or `Stopping the server`).
  Its report says how long nothing has progressed, and the summaries count it as `other`.
- The startup time is measured with the system clock, like the time of Paper's `Done` message, and the steps with a
  monotonic clock. If the system clock is changed while the server starts, the steps no longer add up to the startup
  time; `other` is then at least zero.
- Plugins enabled or disabled while the server runs, for example by a plugin manager, are watched by Paper's
  watchdog and are not part of the summaries.
- When Paper imports a world at startup that it has not run before, it waits 30 seconds before it begins, so that the
  server owner can stop the server. If Minecraft does not need to upgrade the world, the watchdog reports
  `Loading data packs` meanwhile, with the stack trace of the main thread, which only shows that it waits for the
  thread that imports the world. When Minecraft upgrades a world from before 26.1, it does so before the steps begin:
  the watchdog does not watch it, and the summary counts the wait and the upgrade as `other`.
- Work that plugins run on other threads is only visible when it blocks one of the watched steps, for example
  when the thread of a step waits for it.
- The JVM cannot dump virtual threads. When one of them holds the lock a step waits for, the report names it and
  explains how to get a dump that includes virtual threads.

## Watchdog improvements

Spigot's and Paper's watchdog, which reports a server thread that has stopped ticking and restarts the server after
`timeout-time`, is kept as it is: the same settings, the same early warnings every `early-warning-every`
milliseconds after `early-warning-delay`, and the same restart behavior. Cubiq fixes how it waits and makes its
reports more useful:

- **No busy waiting.** While the server thread did not respond, Paper's watchdog skipped its one-second pause
  between checks and spun on a full CPU core until the next early warning, that is exactly when the server already
  struggles. With `early-warning-every: 0` it also spun while the server ran normally, from every check until the
  next tick (about 3% of a core), and with a negative value permanently. Cubiq's watchdog pauses between all checks;
  see [PERFORMANCE.md](PERFORMANCE.md#results) for the measurements.
- **Busy, waiting or stopped.** Every report says how much CPU time the server thread used since its last tick,
  for example `The server thread used 0 ms of CPU time in the last 10003 ms (0%), so it is mostly waiting, for
  example for a lock, a file, the network or a database, or it gets too little CPU time from the operating system.`
  A server thread near 100% is busy, for example in an endless loop. A thread gets no CPU time either while garbage
  collection pauses all threads, so when that takes a tenth of the time or more, the report says for how long, and
  when it takes most of the time, that the server thread is mostly stopped by garbage collection, which happens for
  example when the memory of the server is nearly full.
- **Locks.** The dump of the server thread now includes the monitors it holds and the lock it waits for, and when
  another thread holds that lock, that thread is dumped right after it, because it is usually the actual cause.
  The `java.util.concurrent` locks that a thread holds are left out of the early warnings: finding them pauses all
  threads for a scan of the whole heap, which would make the lag worse. Upstream's full dump on a long timeout
  still lists them. The JVM cannot dump virtual threads, which the server uses for example for
  `AsyncPlayerPreLoginEvent`; when one of them holds the lock, the report names it instead of blaming another
  thread, and explains how to get a dump that includes virtual threads.
  The labels of the dumps are corrected: upstream called the thread id `PID` and listed the locks a thread holds as
  monitors it is "waiting on". An abridged example from Cubiq's watchdog smoke test:

```
The server has not responded for 10 seconds! Creating thread dump
...
The server thread used 0 ms of CPU time in the last 10003 ms (0%), so it is mostly waiting, for example for a lock, a file, the network or a database, or it gets too little CPU time from the operating system.
------------------------------
Current Thread: Server thread
	Thread ID: 51 | Suspended: false | Native: false | State: BLOCKED
	Blocked on: java.lang.Object@28fe70c5 held by "WatchdogSmokeTest lock holder" (thread ID 77)
	Stack:
		WatchdogSmokeTest.jar//studio.cubiq.smoketest.WatchdogSmokeTestPlugin.blockServerThread(WatchdogSmokeTestPlugin.java:42)
		...
------------------------------
The server thread waits for a lock held by "WatchdogSmokeTest lock holder", which is likely the cause:
------------------------------
Current Thread: WatchdogSmokeTest lock holder
	Thread ID: 77 | Suspended: false | Native: false | State: TIMED_WAITING
	Locked monitors:
		java.lang.Object@28fe70c5 locked at WatchdogSmokeTest.jar//studio.cubiq.smoketest.WatchdogSmokeTestPlugin.holdLockInWatchdogSmokeTest(WatchdogSmokeTestPlugin.java:57)
	Stack:
		...
```

Cubiq's additions to the reports catch every failure, even when the server runs out of memory, so that they cannot
end the watchdog thread, which has to halt or restart a server that does not respond.
