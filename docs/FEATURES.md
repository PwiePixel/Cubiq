# Cubiq features

This page describes the features Cubiq adds on top of Purpur and Paper. Everything else behaves as in
Purpur; see [COMPATIBILITY.md](COMPATIBILITY.md).

## Configuration check

CraftBukkit, Spigot and Purpur silently ignore settings they do not know and settings whose value has the
wrong type. A misspelled setting therefore has no effect, and nothing tells the server owner about it.

Once the server has started, Cubiq compares `bukkit.yml`, `spigot.yml` and `purpur.yml` with the settings the
server actually used while starting, and logs every setting that has no effect as a warning of the `Cubiq`
logger, for example:

```
Configuration check: found 3 problems in bukkit.yml, purpur.yml, spigot.yml (37 ms). The following settings have no effect:
  bukkit.yml: Unknown setting 'spawn-limits.monster' has no effect. Did you mean 'spawn-limits.monsters'?
  purpur.yml: Unknown setting 'world-settings.default.mobs.zombie.ridabel' has no effect. Did you mean 'world-settings.default.mobs.zombie.ridable'?
  spigot.yml: 'world-settings.default.mob-spawn-range' must be a number, but is "eight", so it is ignored.
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

How it works:

- While the server starts, the configuration objects of these files record which settings are read, with which
  expected type, and which settings the server writes. Recording stops when the check runs.
- Settings in a world section (`world-settings.<world>.<setting>`) are checked against the settings of
  `world-settings.default`, so settings for worlds that are not loaded are checked as well.
- Sections that the server reads as a whole, such as maps of blocks or entity types, may contain any key.
- The check only reads the files and logs; it never changes them. It runs once per start, after the
  `Done` message, and a failure inside the check is logged without affecting the server.

Limitations:

- Paper's configuration files (`config/paper-global.yml`, `config/paper-world-defaults.yml` and the per-world
  `paper-world.yml`), `server.properties` and `commands.yml` are not checked yet.
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
stuck without any explanation. No upstream server shows either how long each plugin takes to start and stop.

### Summaries

When the server has started, and again when it has stopped, Cubiq logs how long the plugins took. For example,
with the test plugin of Cubiq's smoke test, whose steps each take three seconds:

```
Plugins took 9.0 s to start (bootstrap 3.0 s, load 3.0 s, enable 3.0 s). Plugin: LifecycleSmokeTest 9.0 s.
Stopped the server in 3.0 s. Plugins took 3.0 s to stop. Plugin: LifecycleSmokeTest 3.0 s.
```

With more plugins, the line names the five slowest ones. The time of a plugin is the sum of its bootstrap (Paper
plugins only), `onLoad`, enabling (`onEnable` and the `PluginEnableEvent`) and disabling (`onDisable` and removing
the plugin's tasks, services and listeners). Time that a plugin spends enabling or disabling another plugin counts
for the other plugin. The shutdown line only counts the plugins that the shutdown disabled, not a reload or a plugin
manager that was still busy when the server began to stop. Without plugins, only the shutdown line is logged.

### Lifecycle watchdog

While the server starts, reloads or stops, Cubiq watches each step: bootstrapping the server, bootstrapping,
loading, enabling and disabling each plugin, loading and preparing each world, and saving the worlds on shutdown.
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

Set them on the command line, for example `java -Dcubiq.lifecycleWatchdog.warnAfter=60 -jar cubiq.jar`.

### Limitations

- Slow but healthy work can be reported, for example generating a large world, a plugin that migrates a database
  on its first start, or saving many chunks on shutdown. Repeated stack traces show whether the step progresses,
  unless the thread waits for another thread.
- The `java.util.concurrent` locks that a thread holds are not listed, because finding them pauses all threads for
  a scan of the whole heap. The lock that a thread waits for and the thread that holds it are shown.
- Work between the steps listed above, for example running the tasks that plugins scheduled for the first tick,
  belongs to the enclosing phase (`Starting the server`, `Reloading the server` or `Stopping the server`). Its
  report says how long nothing has progressed.
- Plugins enabled or disabled while the server runs, for example by a plugin manager, are watched by Paper's
  watchdog and are not part of the summaries.
- Work that plugins run on other threads is only visible when it blocks one of the watched steps, for example
  when the thread of a step waits for it.

## Watchdog improvements

Spigot's and Paper's watchdog, which reports a server thread that has stopped ticking and restarts the server after
`timeout-time`, is kept as it is: the same settings, the same early warnings every `early-warning-every`
milliseconds after `early-warning-delay`, and the same restart behavior. Cubiq fixes how it waits and makes its
reports more useful:

- **No busy waiting.** While the server thread did not respond, Paper's watchdog skipped its one-second pause
  between checks and spun on a full CPU core until the next early warning, that is exactly when the server already
  struggles. With `early-warning-every: 0` it did so between all ticks. Cubiq's watchdog pauses between all checks;
  see [PERFORMANCE.md](PERFORMANCE.md#results) for the measurements.
- **Busy or waiting.** Every report says how much CPU time the server thread used since its last tick, for example
  `The server thread used 0 ms of CPU time in the last 10003 ms (0%), so it is mostly waiting, for example for a
  lock, a file, the network or a database.` A server thread near 100% is busy, for example in an endless loop.
- **Locks.** The dump of the server thread now includes the monitors it holds and the lock it waits for, and when
  another thread holds that lock, that thread is dumped right after it, because it is usually the actual cause.
  The `java.util.concurrent` locks that a thread holds are left out of the early warnings: finding them pauses all
  threads for a scan of the whole heap, which would make the lag worse. Upstream's full dump on a long timeout
  still lists them.
  The labels of the dumps are corrected: upstream called the thread id `PID` and listed the locks a thread holds as
  monitors it is "waiting on". An abridged example from Cubiq's watchdog smoke test:

```
The server has not responded for 10 seconds! Creating thread dump
...
The server thread used 0 ms of CPU time in the last 10003 ms (0%), so it is mostly waiting, for example for a lock, a file, the network or a database.
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
