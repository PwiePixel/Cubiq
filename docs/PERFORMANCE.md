# Performance

Cubiq must only claim performance improvements that are backed by reproducible measurements. This document
describes the current state, the measurement method, and every result. When a change is made for performance
reasons, its benchmark results belong here.

## Current state

**No benchmarks of the server as a whole have been run yet.** Cubiq currently executes Purpur's code with additional
diagnostics, so it is expected to perform like Purpur at the pinned upstream commits, but this has not been measured,
and Cubiq makes no claim about its overall performance.

The only measured performance change so far is narrow: the [watchdog fix](FEATURES.md#watchdog-improvements) removes
the CPU time the watchdog thread wasted while the server did not respond. The measurements are listed under
[Results](#results).

### Cost of Cubiq's changes so far (analysis, not measurements)

- **Configuration tracking** (`TrackedYamlConfiguration`): every read of `bukkit.yml`, `spigot.yml` or
  `purpur.yml` settings additionally touches a thread-local counter and, while the server starts, merges a flag
  into a concurrent map. The upstream code reads these settings during startup and caches them in fields; only a
  few CraftBukkit getters read `bukkit.yml` at runtime. Recording stops when the configuration check has run.
- **Configuration check**: runs once on the server thread right after startup and parses the three files again.
  Its duration is logged with its result so that it can be monitored on real servers.
- **Watchdog**: Cubiq's watchdog reads the CPU time of the server thread once per check (once per second), on the
  watchdog thread, and captures lock information only when it writes a report. The watchdog fix removes CPU use
  (see the results below).
- **Lifecycle diagnostics**: each plugin step, world load and lifecycle phase opens and closes a small tracking
  object (a few per plugin and world, so a few hundred on large servers), which takes an uncontended lock and
  reads the clock. Plugins enabled or disabled at runtime are tracked the same way. The watchdog thread wakes up
  once per second; while the server runs normally it only finds that no phase is open. Stack traces are only taken
  when a step is reported.

## Available measurement tools

Inherited from Paper:

- [spark](https://spark.lucko.me/) is bundled with the server and provides CPU and allocation profiling, tick
  monitoring and GC statistics.
- `/mspt` and `/tps` report the average, minimum and maximum tick times and the recent TPS.
- Java Flight Recorder (`-XX:StartFlightRecording`) works as on any JDK 25 application.

## Benchmark method

Benchmarks compare Cubiq with Paper and Purpur builds for the same Minecraft version under identical conditions.

**Record for every run:** hardware (CPU model, cores, memory), operating system, JDK vendor and version, JVM
flags, the exact server builds (Cubiq commit, Paper and Purpur build numbers), all configuration files, the world
(seed or copy), the plugins, and the date.

**Workloads:**

| Workload | What it measures |
| --- | --- |
| Startup on an existing world | Time to `Done`, startup allocation |
| Chunk generation (for example a fixed radius around spawn) | Generation and I/O throughput, worker thread utilization |
| Entity-heavy area (fixed numbers of mobs and items) | Entity ticking and collision cost |
| Redstone-heavy area (fixed machines) | Block update and scheduled tick cost |
| Simulated players moving through the world | Chunk loading, network and player ticking |
| Long run (several hours) | Memory growth, GC behavior, stability |

**Metrics:** tick time percentiles (p50, p95, p99, maximum) rather than only averages, TPS, CPU time per tick,
allocation rate, GC pause times and frequency, heap after GC, and time to `Done`.

**Procedure:** warm up before measuring, run each configuration at least five times, alternate the server builds
between runs, report the median together with the spread, and keep the raw data. A difference is only reported as
an improvement when it is larger than the run-to-run variation.

## Requirements and blockers

- Running a server, even only for benchmarks, requires accepting the Minecraft EULA. The project owner accepted it
  for test servers, locally and in CI, on 2026-10-28.
- Shared CI runners are noisy. Comparative benchmarks need dedicated or at least consistent hardware; CI should
  only be used to catch large regressions.

## Results

No benchmarks of the whole server yet. Individual measurements taken during development. "Before the fix" means Cubiq
at commit `ac58dc1`, whose watchdog is unchanged from Paper and Purpur:

| Date | What | Result | Conditions |
| --- | --- | --- | --- |
| 2026-10-28 | CPU time of the watchdog thread while a test plugin blocks the server thread for 15 s, default watchdog settings | 8.13 s before the [watchdog fix](FEATURES.md#watchdog-improvements), 0.01 s after | One run each; CPU time of the `Watchdog Thread` read from `/proc` once per second; 4 vCPU cloud container, Amazon Corretto 25.0.5, `-Xms1G -Xmx2G` |
| 2026-10-28 | CPU time of the watchdog thread while the server runs normally with `early-warning-every: 0`, over 30 s | 0.98 s before the fix, 0.01 s after | As above, without a test plugin |
| 2026-10-28 | CPU time of the watchdog thread while the test plugin of the watchdog smoke test blocks the server thread for 13 s on a lock | 6287 ms before the fix, 11 to 12 ms after | As above; measured by the test plugin with `ThreadMXBean#getThreadCpuTime` |
| 2026-10-28 | Duration of the configuration check after startup (commit `182ca98`) | 67 ms on the first start, 36 ms on the second start, 29 ms with 8 injected mistakes | One run each; configuration files generated by the server, edited for the third start; 4 vCPU cloud container, Amazon Corretto 25.0.5, `-Xms1G -Xmx2G`; measured by the check itself |
