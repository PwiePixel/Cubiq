# Compatibility

Cubiq aims to run every plugin and configuration that works on Purpur and Paper for the same Minecraft version.
This page records what has been verified, what is inherited unchanged, and where Cubiq intentionally differs.

## Supported versions

| | |
| --- | --- |
| Minecraft: Java Edition | 26.3 |
| Java | 25 or newer (required by Minecraft 26.3 and Paper 26.3) |
| Upstream | Purpur `087d0657` (2026-10-24), which pins Paper `e3ba7b1d` |

## Status legend

- **Verified**: covered by tests that run in Cubiq's CI.
- **Inherited**: unchanged Paper or Purpur behavior. Cubiq runs the upstream test suites, but has not verified the
  behavior independently, for example with real plugins.
- **Changed**: Cubiq intentionally behaves differently; the reason is given.
- **Not supported**

## Matrix

| Area | Status | Notes |
| --- | --- | --- |
| Bukkit and Spigot API | Inherited | Unchanged. Upstream's API and server test suites pass in Cubiq's build. |
| Paper API (Adventure, Brigadier commands, lifecycle events, registry API, `paper-plugin.yml` plugins) | Inherited | Unchanged. |
| Purpur API and `purpur.yml` | Inherited | Unchanged. |
| Folia scheduler API (`RegionScheduler`, `EntityScheduler`, `GlobalRegionScheduler`, `AsyncScheduler`) | Inherited | Implemented as in Paper: region, entity and global tasks run on the server thread. Plugins that declare `folia-supported: true` run as they do on Paper. |
| Folia's regionized multithreading | Not supported | Cubiq ticks worlds on one server thread, like Paper. See [ROADMAP.md](ROADMAP.md). |
| Server brand | Changed, verified | `ServerBuildInfo#brandId()` is `cubiqstudio:cubiq` and `Bukkit.getName()` returns `Cubiq`. `ServerBuildInfo#isBrandCompatible` accepts the Paper, Purpur and Cubiq brand ids, so plugins that check for Paper or Purpur that way keep working. |
| `/version` update check | Changed, verified | Cubiq does not query the Paper or Purpur download APIs, which would report misleading results for Cubiq builds. There is no release API for Cubiq yet. |
| bStats server metrics | Changed | Cubiq does not submit server-implementation metrics, because it has no registered bStats page and must not report as Purpur. `plugins/bStats/config.yml` is still created, so plugins that use bStats are unaffected. |
| Configuration files (`server.properties`, `bukkit.yml`, `spigot.yml`, `config/paper-*.yml`, `purpur.yml`) | Inherited | Read and written as by Purpur. Cubiq adds no configuration file. The [configuration check](FEATURES.md#configuration-check) only logs and never changes them. |
| Chunk system system properties (`-DPaper.WorkerThreadCount`, `-DPaper.MaxViewDistance`, `-DPaper.NumaScheduling`) | Inherited | Same names as on Paper. The chunk system builds these names from its platform brand, which Cubiq deliberately leaves at `Paper`. |
| Console and log output | Changed | Cubiq adds messages of the `Cubiq` logger: the result of the configuration check, how long the plugins took to start and stop, and reports of the [lifecycle watchdog](FEATURES.md#lifecycle-diagnostics). Upstream messages are unchanged apart from the rebranded crash and watchdog texts and the [watchdog dumps](FEATURES.md#watchdog-improvements), which gain a CPU line and lock information and use the labels `Thread ID`, `Blocked on`, `Waiting on` and `Locked monitors`. |
| Watchdog (`spigot.yml` `timeout-time`, `restart-on-crash`; `paper-global.yml` `watchdog`) | Changed | Same settings, early warnings, timeouts and restart behavior. It no longer spins on a CPU core while the server does not respond. |
| World and player data | Inherited | Cubiq stores no data of its own, so worlds stay compatible with Paper and Purpur 26.3. |
| Proxies (Velocity, BungeeCord) | Inherited | Not tested with Cubiq yet. |
| Third-party plugins | Not verified | No plugin compatibility tests exist yet; they are planned for phase 2 of the roadmap. |

## Known differences that can affect plugins

- **Plugins that compare `Bukkit.getName()` with `"Paper"` or `"Purpur"`** do not recognize Cubiq, exactly as they
  do not recognize Purpur when they look for Paper. The supported way to detect a compatible server is
  `ServerBuildInfo.buildInfo().isBrandCompatible(ServerBuildInfo.BRAND_PAPER_ID)` or checking for a class of the
  required API.
- **`Bukkit.getTPS()` returns four values** (the averages of the last 5 seconds, 1, 5 and 15 minutes), as on
  Purpur, instead of Paper's three (1, 5 and 15 minutes). `getTPS()[0]` is therefore the 5-second average.
- **Plugins that read `bukkit.yml`, `spigot.yml` or `purpur.yml` themselves** keep working. If they read settings
  that the server does not use, the configuration check may report those settings; it can be disabled with
  `-Dcubiq.configCheck=false`.

## Switching to Cubiq

- **From Purpur 26.3:** replace the server jar. All configuration files are used as before.
- **From Paper 26.3:** replace the server jar. On first start, Purpur's `purpur.yml` is created with its defaults;
  review it after the first start.
- **Back to Purpur or Paper 26.3:** possible, because Cubiq does not change any data format.

## What is verified in CI

Every push builds Cubiq from scratch (applying all patches) and runs:

- upstream's API, server and checkstyle test suites,
- Cubiq's unit tests (`studio.cubiq.testing.CubiqUnitTestSuite`),
- Cubiq's tests against the real Spigot, Purpur and CraftBukkit configuration code
  (`studio.cubiq.testing.CubiqNormalTestSuite`),
- the creation of the Paperclip jar,
- a smoke test that starts the Paperclip jar twice (first start and a start with existing files), checks that the
  server reaches `Done`, that the configuration check finds no problems in the generated files, and that the server
  stops cleanly (`scripts/smoke-test.sh`), and that the lifecycle watchdog reports nothing,
- a lifecycle smoke test that starts the server with a test plugin whose bootstrap, load, enable and disable each
  take three seconds, and checks that the lifecycle watchdog reports every step with the plugin's stack frames and
  that both summaries name the plugin (`scripts/lifecycle-smoke-test.sh`),
- a watchdog smoke test that blocks the server thread for 15 seconds on a lock held by another thread and checks
  that the watchdog reports it with the CPU use, the lock and the thread that holds it, without a restart, and
  that the watchdog thread used at most 1.5 seconds of CPU time meanwhile (`scripts/watchdog-smoke-test.sh`).

Connecting clients and running real plugins are not automated yet.
