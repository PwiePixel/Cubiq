# Roadmap

This roadmap is specific to Minecraft 26.3 and the current upstream pins. It is ordered by dependency: later
phases build on the foundations of earlier ones. Items are only marked done when they are implemented, tested
and pushed.

## Phase 1: Foundation

- [x] Build Cubiq as a paperweight patch set on Purpur, pinned to exact upstream commits
- [x] Rebrand without breaking plugins (brand id, version check, crash and watchdog messages, bStats identity)
- [x] Package license texts and notices in the server jar; document the licensing ([LICENSING.md](LICENSING.md))
- [x] Continuous integration: apply patches, build, run upstream and Cubiq tests, build the Paperclip jar
- [x] Reproducible jar manifests (build time taken from the commit in CI)
- [x] Cubiq test suites for plain unit tests and for tests in the upstream `@Normal` environment
- [x] First Cubiq feature: the [configuration check](FEATURES.md#configuration-check)
- [x] Decision on the Minecraft EULA for test servers: accepted by the project owner for local tests and CI
- [x] Smoke test in CI: start a server twice, wait for `Done`, require a clean configuration check and a clean stop
- [x] Lifecycle smoke test in CI: a test plugin with slow lifecycle steps must be reported by the lifecycle watchdog
- [ ] Release process: CI publishes every build of `main` that passes all checks as a numbered pre-release
      (`vMAJOR.MINOR.PATCH`, starting at v0.0.1) with the Paperclip jar, its SHA-256, the source code of MySQL
      Connector/J that its license requires, and notes that list the changes and the exact upstream commits, and
      `/version` shows the release; a snapshot pre-release holds a recent development build
      ([CONTRIBUTING.md](CONTRIBUTING.md#releases)). Implemented and tested on a branch (version numbering, the notes,
      a release build on a test server, the steps of the jobs with a stand-in for `gh`), and the snapshot job has
      published a snapshot; the release job runs for the first time when `main` contains it
- Not planned: a source archive with the upstream sources for each release. The project owner decided that releases
  refer to the tag and the upstream commits instead (see [LICENSING.md](LICENSING.md)).
- [x] Reproducible builds: two builds of the same commit in the same environment produce an identical server jar and
      Paperclip jar (checked in CI)
- [x] Reproducible across machines: builds of two commits on another machine, with a different JDK vendor and update
      and a different operating system, produced Paperclip jars identical to those of CI; what has to match is
      documented in [CONTRIBUTING.md](CONTRIBUTING.md)
- [x] Add Cubiq's license files to the Paperclip jar itself, not only to the server jar that Paperclip creates
- [x] CI checks on the distributed jar: no Minecraft classes as entries of the Paperclip jar, only the server patch;
      license files present
- [x] CI check that rebuilding the patches from the patched sources changes nothing, so that the committed patches
      always match what was built
- [x] Generate an inventory of all bundled libraries and their licenses in CI, with an allowlist check
      ([LIBRARIES.md](LIBRARIES.md))
- [x] Ship the license texts and copyright notices that the bundled libraries' jars lack (43 of the 67 jars of other
      projects contain no license or notice file, 7 more lack notices or a source pointer) and those of code of other
      projects compiled into the server jar and the Paperclip jar, collected from each project's source at the bundled
      version and checked in CI ([THIRD-PARTY-NOTICES.md](../licenses/THIRD-PARTY-NOTICES.md))
- [x] Decide the licensing questions that collecting the notices raised (decisions in [LICENSING.md](LICENSING.md))
- [x] Leave out the bundled code whose license the FSF lists as incompatible with the GPL where the server does not
      need it: Eclipse Sisu Inject (plugin libraries still load, checked on a server) and the 32-bit macOS library
      of jline-native
- [x] Cubiq logo for the server GUI, instead of the logo inherited from Purpur

## Diagnostics and administration (ongoing)

Low-risk features that give server owners information upstream does not provide. Each one only observes and
reports, and is covered by tests. In order of priority:

- [x] Configuration check for `bukkit.yml`, `spigot.yml` and `purpur.yml`
- [x] Configuration check for Paper's configuration files (`config/paper-global.yml`,
      `config/paper-world-defaults.yml` and the `paper-world.yml` of each world)
- [x] Configuration check for `server.properties`: unknown settings, settings that game rules or Paper's
      configuration replaced, and values that Minecraft does not use as written
- [x] [Lifecycle watchdog](FEATURES.md#lifecycle-diagnostics): stack traces when startup, `/reload`, a plugin's
      bootstrap, load, enable or disable, loading a world or shutdown stops making progress. The upstream watchdog is
      only armed between `Done` and the start of shutdown, so hangs in these phases were never reported.
- [x] Per-plugin lifecycle timing (bootstrap, load, enable, disable) with summaries after startup and shutdown
- [x] [Watchdog hardening](FEATURES.md#watchdog-improvements): no busy waiting while the server does not respond, CPU
      use of the server thread, locks and the thread that holds the lock the server thread waits for
- [x] A [breakdown of startup and shutdown time](FEATURES.md#summaries) by step (bootstrapping, data packs, server
      setup, plugins, worlds per world, saving) in the log, and an optional [JSON report](FEATURES.md#report-for-tools)
      for tools such as the benchmark harness
- [x] Stop without waiting up to 3 seconds for the search for stronghold positions that Minecraft starts when a world
      loads, which the breakdown showed as unexplained time when a server stopped right after it started; the breakdown
      shows the time of background tasks and of the pauses of `/restart`
- [ ] Opt-in thread-context assertions that report, with plugin attribution, world access from the wrong thread
      (see phase 4)
- Not planned: tick time percentiles and tick-thread utilization in `/mspt`. The bundled spark already shows the
  minimum, median, 95th percentile and maximum tick duration of the last 10 seconds and minute in `/spark tps`;
  compared with the 50 ms of a tick, they also show how busy the server thread is.
- [ ] Chunk system throughput (chunks loaded and generated per second) in `/tps`, based on Folia's counters

## Phase 2: Compatibility

- [ ] Plugin compatibility matrix: run a set of widely used plugins (permissions, world management, protection,
      economy, placeholders, Folia-supported plugins) against Cubiq in an automated test server
- [ ] API conformance tests for the brand and version APIs used by plugins to detect server software
- [x] Extend the configuration check to Paper's configuration files (`paper-global.yml`, world defaults and
      per-world files)
- [x] Extend the configuration check to `server.properties`
- [x] Keep the settings of `server.properties` that Paper dropped when it moved `allow-nether` out of the file while
      updating `paper-global.yml` from version 30 or older, including Paper's own `rcon.ip`, which made RCON listen on
      `server-ip` afterwards
- [ ] Extend the configuration check to `commands.yml`
- [ ] A command to re-run the configuration check after reloads, and an optional machine-readable report
- [ ] Document configuration differences between Paper, Purpur and Cubiq defaults

## Phase 3: Performance

- [ ] Benchmark harness following [PERFORMANCE.md](PERFORMANCE.md) (startup, chunk generation, entity, redstone and
      player workloads) with stored raw results
- [ ] Baseline measurements of Paper, Purpur and Cubiq 26.3 on the same hardware
- [ ] Profile the slowest workloads with spark and JFR; record the bottlenecks
- [ ] Optimizations only where profiling shows a cost, each with before/after measurements and tests that show
      unchanged game behavior
- [ ] Regression benchmarks for optimized code paths

## Phase 4: Advanced scheduling (Folia-inspired)

Cubiq keeps Bukkit plugin compatibility, so it will not adopt Folia's model of ticking regions of a world on
different threads, which requires plugins to be written for it. Instead, it will introduce selected concepts
incrementally:

- [ ] Write down the thread-safety contracts of the server: which thread owns which state, which APIs may be called
      from where ([ARCHITECTURE.md](ARCHITECTURE.md))
- [ ] Ownership diagnostics: an opt-in mode that reports which plugin accesses world state from the wrong thread,
      with stack traces and plugin attribution. On Paper, the Folia ownership checks
      (`Bukkit.isOwnedByCurrentRegion` and related methods) only check for the server thread. The server itself
      accesses some state off the server thread: Paper's asynchronous chat processing calls `CraftPlayer#getHandle`
      (Folia exempts that method from its checks), and Purpur's TPS, RAM and compass boss bars are updated by an
      asynchronous task that looks up players and, for the compass bar, reads their held items and rotation. These
      cases must be fixed or exempted before such a mode is useful.
- [ ] A report of which installed plugins declare `folia-supported`, without enforcing anything
- [ ] Shadow regions: compute how each world would split into independent regions, as Folia's regionizer does,
      without ticking them in parallel, to measure whether regionized ticking would pay off on real servers
- [ ] Measure how much time the server thread spends on work that does not depend on world state and could move to
      other threads without changing behavior
- [ ] Region-aware scheduling foundations only for workloads where the dependencies are proven independent, each
      with ordering, shutdown and race-condition tests
- [ ] Re-evaluate deeper concurrency once the foundations are in place and measured

## Phase 5: Production readiness

- [ ] Long-running stability tests (memory, GC, chunk and entity counts over many hours)
- [ ] Error recovery and diagnostics review for startup, shutdown and watchdog paths
- [ ] Security review of Cubiq's changes and of the build and release pipeline
- [ ] Compatibility verification with the plugin matrix on every release
- [ ] Reproducible, signed release builds
- [ ] Release readiness assessment, based on the evidence above, before calling any build production-ready

## Keeping up with upstream

Upstream updates are part of every phase: the `purpurCommit` pin is updated regularly, and new Minecraft versions
are adopted after Paper and Purpur support them. Each update re-runs the full test suite before it is merged.
