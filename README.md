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

- **Configuration check.** After startup, Cubiq reports settings in `bukkit.yml`, `spigot.yml` and
  `purpur.yml` that have no effect, such as misspelled settings or values of the wrong type, and suggests
  the setting that was probably meant. See [FEATURES.md](docs/FEATURES.md).
- **Lifecycle diagnostics.** Cubiq logs how long the plugins take to start and stop, and reports steps of startup,
  `/reload` and shutdown that stop making progress, such as a plugin that hangs in `onDisable`, with a stack trace.
  Paper's watchdog does not watch these phases. See [FEATURES.md](docs/FEATURES.md#lifecycle-diagnostics).
- **Watchdog fix.** The inherited watchdog no longer spins on a CPU core while the server does not respond, and its
  reports say whether the server thread is busy or waiting, which lock it waits for and which thread holds it.
  See [FEATURES.md](docs/FEATURES.md#watchdog-improvements).
- Cubiq identifies itself as Cubiq (brand id `cubiqstudio:cubiq`). Plugins that check for Paper or
  Purpur through `ServerBuildInfo#isBrandCompatible` keep working.
- `/version` does not query Paper's or Purpur's download APIs for Cubiq builds, which would report
  misleading update information.
- Crash reports and watchdog messages ask users to report problems to Cubiq instead of Paper or Purpur.
- The server does not submit server-implementation statistics to bStats under Purpur's name.
  Plugins that use bStats are not affected.
- License texts and notices are packaged in the server jar under `META-INF/license/`.

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
