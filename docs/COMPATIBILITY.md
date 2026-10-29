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
| `/version` update check | Changed, verified | Cubiq does not query the Paper or Purpur download APIs, which would report misleading results for Cubiq builds. A [release](CONTRIBUTING.md#releases) shows its version (`You are running Cubiq v0.0.1`) and links to Cubiq's releases; other builds say that they are development builds. Cubiq does not check for newer releases yet. |
| bStats server metrics | Changed | Cubiq does not submit server-implementation metrics, because it has no registered bStats page and must not report as Purpur. `plugins/bStats/config.yml` is still created, so plugins that use bStats are unaffected. |
| Configuration files (`server.properties`, `bukkit.yml`, `spigot.yml`, `config/paper-*.yml`, `purpur.yml`) | Changed | Read and written as by Purpur, with one fix: when Paper updates `paper-global.yml` from a version before 31 and moves `allow-nether` out of `server.properties`, Paper and Purpur write `server.properties` with only the settings that Minecraft writes back. That loses all other settings, including Paper's own `rcon.ip`, so that RCON listens on `server-ip` from the next start. Cubiq keeps them and only removes `allow-nether`. Cubiq adds no configuration file. The [configuration check](FEATURES.md#configuration-check) only logs and never changes them. |
| Chunk system system properties (`-DPaper.WorkerThreadCount`, `-DPaper.MaxViewDistance`, `-DPaper.NumaScheduling`) | Inherited | Same names as on Paper. The chunk system builds these names from its platform brand, which Cubiq deliberately leaves at `Paper`. |
| Console and log output | Changed | Cubiq adds messages of the `Cubiq` logger: the result of the configuration check, which steps of starting and stopping the server and which plugins took how long, and reports of the [lifecycle watchdog](FEATURES.md#lifecycle-diagnostics). Upstream messages are unchanged apart from the rebranded crash and watchdog texts and the [watchdog dumps](FEATURES.md#watchdog-improvements), which gain a CPU line and lock information and use the labels `Thread ID`, `Blocked on`, `Waiting on` and `Locked monitors`. |
| Watchdog (`spigot.yml` `timeout-time`, `restart-on-crash`; `paper-global.yml` `watchdog`) | Changed | Same settings, early warnings, timeouts and restart behavior. It no longer spins on a CPU core while the server does not respond. |
| Stopping the server | Changed | Once everything is saved, Cubiq ends the searches for stronghold positions that have not started yet, which Minecraft runs in the background for a few seconds after a world loads, instead of waiting up to 3 seconds for them like Paper and Purpur. The positions are never saved, and the next start searches them again. |
| World and player data | Inherited | Cubiq stores no data of its own, so worlds stay compatible with Paper and Purpur 26.3. |
| Proxies (Velocity, BungeeCord) | Inherited | Not tested with Cubiq yet. |
| Third-party plugins | Not verified | No plugin compatibility tests exist yet; they are planned for phase 2 of the roadmap. |

## Known differences that can affect plugins

- **Plugins that compare `Bukkit.getName()` with `"Paper"` or `"Purpur"`** do not recognize Cubiq, exactly as they
  do not recognize Purpur when they look for Paper. The supported way to detect a compatible server is
  `ServerBuildInfo.buildInfo().isBrandCompatible(ServerBuildInfo.BRAND_PAPER_ID)` or checking for a class of the
  required API.
- **The build number counts Cubiq's releases.** A release reports a version such as `26.3-1-main@abc1234`, and
  `ServerBuildInfo#buildNumber()` is 1 for v0.0.1, 2 for the next release and so on; other builds have no build
  number and report `DEV` in its place, like Paper's development builds. Plugins that compare the build number with
  Paper's builds get meaningless results, as they do on Purpur, whose build numbers are its own as well.
- **Eclipse Sisu Inject is not bundled**, unlike on Paper, because its license is not compatible with the GPL; the
  server does not use it. A plugin that uses its classes has to load it through the `libraries` of its `plugin.yml`.
- **`Bukkit.getTPS()` returns four values** (the averages of the last 5 seconds, 1, 5 and 15 minutes), as on
  Purpur, instead of Paper's three (1, 5 and 15 minutes). `getTPS()[0]` is therefore the 5-second average.
- **Plugins that read `server.properties`, `bukkit.yml`, `spigot.yml` or `purpur.yml` themselves** keep working. If
  they read settings that the server does not use, the configuration check may report those settings; it can be
  disabled with `-Dcubiq.configCheck=false`.

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
- a check that rebuilding the patches from the patched sources reproduces the committed patches exactly,
- the creation of the Paperclip jar,
- a smoke test that starts the Paperclip jar twice (first start and a start with existing files), checks that the
  server reaches `Done`, that the configuration check finds no problems in the generated files, that the lifecycle
  watchdog reports nothing and that the server stops cleanly (`scripts/smoke-test.sh`),
- a configuration check smoke test that puts a mistake into each kind of checked file, four of them into
  `server.properties`, and checks that the configuration check reports each of them with the expected message, then
  starts again with `paper-global.yml` set back to version 30, so that Paper moves `allow-nether` and rewrites
  `server.properties`, and checks that the other settings, including `rcon.ip`, stay in the file and that
  `allow-nether` is not reported (`scripts/config-smoke-test.sh`),
- a lifecycle smoke test that starts the server with a test plugin whose bootstrap, load, enable and disable each take
  three seconds, and checks that the lifecycle watchdog reports every step with the plugin's stack frames, that the
  summaries name the plugin and break startup and shutdown down into their steps and worlds, and that the JSON report
  has the same steps and the plugin's times, and a second run that stops the server with `/restart` instead of `stop`,
  whose summary must show kicking the players as a step of its own (`scripts/lifecycle-smoke-test.sh`),
- a watchdog smoke test that blocks the server thread for 15 seconds on a lock held by another thread and checks
  that the watchdog reports it with the CPU use, the lock and the thread that holds it, without a restart, and
  that the watchdog thread used at most 1.5 seconds of CPU time meanwhile; then it keeps the server thread busy for
  12 seconds and checks that the report calls it busy, and that the two stalls caused 2 to 4 early warnings
  (`scripts/watchdog-smoke-test.sh`),
- a check of the distribution: the Paperclip jar and the server jar it created contain the license texts and
  notices, unchanged, and the Paperclip jar contains no Minecraft or Mojang classes and no Mojang libraries as
  entries of their own, only a patch against Mojang's jar, which Paperclip downloads from Mojang
  (`scripts/check-distribution.sh`),
- a check that every bundled library has a license that `scripts/library-licenses.json` covers and license notices,
  in its jar or in `licenses/third-party/` for its bundled version, and that [LIBRARIES.md](LIBRARIES.md) and
  `licenses/THIRD-PARTY-NOTICES.md` are up to date (`scripts/library-licenses.py`). Whether the license files in a
  library's jar are complete is not checked automatically,
- a second build that reuses no output of the first build and must produce an identical server jar and Paperclip jar;
  the SHA-256 of the Paperclip jar is shown in the summary of the CI run.

Connecting clients and running real plugins are not automated yet.
