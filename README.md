<img src="docs/assets/cubiq-logo.png" alt="Cubiq logo" width="160" align="right">

# Cubiq

Cubiq is Minecraft: Java Edition server software developed by **Cubiq Studio**. It is built on
[Purpur](https://github.com/PurpurMC/Purpur) and [Paper](https://github.com/PaperMC/Paper) and aims to
combine Paper's performance work and plugin compatibility, Purpur's configurability, and carefully
engineered, Folia-inspired scalability features.

> **Status: early development (alpha).** Cubiq currently consists of Purpur plus Cubiq's own build
> infrastructure, branding, a configuration check, lifecycle diagnostics and tests. It has not been benchmarked
> or validated on production servers yet, and no performance claims are made. Do not use it for production
> servers.

| | |
| --- | --- |
| Minecraft version | 26.3 |
| Java version | 25 or newer |
| Upstream | Purpur (pinned in `gradle.properties`), which is based on Paper |
| Plugin APIs | Bukkit, Spigot, Paper and Purpur, inherited from upstream; see [COMPATIBILITY.md](docs/COMPATIBILITY.md) |
| License | Apache 2.0 for Cubiq's own work; built servers are GPLv3; see [LICENSING.md](docs/LICENSING.md) |

## Goals

1. Measurable performance improvements, backed by reproducible benchmarks ([PERFORMANCE.md](docs/PERFORMANCE.md)).
2. Compatibility with existing Bukkit, Spigot, Paper and Purpur plugins and configuration.
3. Better scalability through region-aware features that are introduced incrementally and without
   breaking plugin compatibility.
4. Stability, diagnostics and administration features that give server owners real value.

See [ROADMAP.md](docs/ROADMAP.md) for the plan and [ARCHITECTURE.md](docs/ARCHITECTURE.md) for how
Cubiq is put together.

## Differences from Purpur

- **Configuration check.** After startup, Cubiq reports settings in `server.properties`, `bukkit.yml`,
  `spigot.yml`, `purpur.yml` and Paper's configuration files that do not work as written, such as misspelled
  settings, settings that game rules replaced, or values the server cannot use (Minecraft reads `white-list=true `
  with a space at the end as `false`), and suggests the setting that was probably meant. See
  [FEATURES.md](docs/FEATURES.md).
- **Lifecycle diagnostics.** Cubiq logs how long the plugins take to start and stop, and reports steps of startup,
  `/reload` and shutdown that stop making progress, such as a plugin that hangs in `onDisable`, with a stack trace.
  Paper's watchdog does not watch these phases. See [FEATURES.md](docs/FEATURES.md#lifecycle-diagnostics).
- **Watchdog fix.** The inherited watchdog no longer spins on a CPU core while the server does not respond, and its
  reports say whether the server thread is busy, waiting or stopped by garbage collection, which lock it waits for
  and which thread holds it.
  See [FEATURES.md](docs/FEATURES.md#watchdog-improvements).
- Cubiq identifies itself as Cubiq (brand id `cubiqstudio:cubiq`). Plugins that check for Paper or
  Purpur through `ServerBuildInfo#isBrandCompatible` keep working.
- `/version` does not query Paper's or Purpur's download APIs for Cubiq builds, which would report
  misleading update information. It shows which Cubiq release the server runs and links to Cubiq's releases.
- Crash reports and watchdog messages ask users to report problems to Cubiq instead of Paper or Purpur.
- The server does not submit server-implementation statistics to bStats under Purpur's name.
  Plugins that use bStats are not affected.
- License texts and notices are packaged in the Paperclip jar and the server jar under `META-INF/license/`, including
  those that the jars of the bundled libraries lack and those of code of other projects compiled into the server
  ([THIRD-PARTY-NOTICES.md](licenses/THIRD-PARTY-NOTICES.md)), and [LIBRARIES.md](docs/LIBRARIES.md) lists the bundled
  libraries with their licenses, checked in CI.
- Builds are reproducible: two builds of the same commit in the same environment produce an identical server jar and
  Paperclip jar, which CI checks, and builds on another machine, with a different JDK and operating system, produced
  the same Paperclip jar as CI.

## Download

Every change on `main` that passes all checks in CI is published as a numbered pre-release on the
[releases page](https://github.com/PwiePixel/Cubiq/releases), starting with v0.0.1. A release contains the Paperclip
jar, for example `cubiq-26.3-v0.0.1.jar`, and its SHA-256 checksum, and its notes list the changes since the previous
release and the Purpur and Paper commits it is based on. Check the jar and start it with Java 25:

```bash
sha256sum -c cubiq-26.3-v0.0.1.jar.sha256
java -jar cubiq-26.3-v0.0.1.jar --nogui
```

The [snapshot](https://github.com/PwiePixel/Cubiq/releases/tag/snapshot), `cubiq-26.3-snapshot.jar`, is the newest
development build, ahead of the latest release. Every build of a development branch that passes all checks replaces
it, so it can contain unfinished changes; use it for testing only.

Releases and the snapshot are alpha software like everything else in Cubiq: back up your worlds before you try one.
On first start, the jar downloads the Minecraft server from Mojang, and running it requires accepting the
[Minecraft EULA](https://aka.ms/MinecraftEULA). Each release and the snapshot also contain the source code of MySQL
Connector/J, a bundled library whose license, the GPLv2, requires it.

## Building

Requirements: JDK 25 and Git. Clone the repository with Git (zip downloads cannot be built), then run:

```bash
./gradlew applyAllPatches
./gradlew createPaperclipJar
```

The server jar is written to `cubiq-server/build/libs/cubiq-paperclip-<version>.jar`. On first start
it downloads the Minecraft server from Mojang and applies Cubiq's patches. Running a server requires
accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA).

See [CONTRIBUTING.md](docs/CONTRIBUTING.md) for the development workflow.

## Reporting problems

Please report problems with Cubiq at <https://github.com/PwiePixel/Cubiq/issues>, not to the Paper or
Purpur projects.

## Acknowledgements

Cubiq would not exist without the work of the Paper, Purpur, Spigot, CraftBukkit and Bukkit
contributors. Their license notices are preserved in [`licenses/`](licenses) and [`NOTICE`](NOTICE).

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
