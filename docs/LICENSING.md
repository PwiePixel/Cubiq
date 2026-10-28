# Licensing

This document describes how the licenses of Cubiq and the projects it is built from fit together.
It is a factual summary for maintainers and server owners, not legal advice. Where a decision is up
to the Cubiq maintainers, it is marked as such.

## Summary

| Part | License | Where |
| --- | --- | --- |
| Cubiq's own work: build scripts, Cubiq classes under `studio.cubiq`, Cubiq's patch hunks, documentation | Apache License 2.0 | `LICENSE`, `NOTICE` |
| Paper, and through Paper: Spigot, CraftBukkit and Bukkit | GNU GPL v3; contributions by the authors listed in Paper's `LICENSE.md` are also available under MIT | `licenses/Paper-LICENSE.md`, `licenses/GPL-3.0.txt`, `licenses/Paper-MIT.md` |
| Purpur's own patches and classes | MIT, Copyright (c) 2019-2024 PurpurMC | `licenses/Purpur-LICENSE.txt` |
| Minecraft server | Proprietary (Mojang/Microsoft), subject to the Minecraft EULA | Not distributed by Cubiq |
| Built Cubiq server (combined work) | GNU GPL v3 | `licenses/GPL-3.0.txt` |

## Upstream licenses

- **Paper** states that it inherits the GNU General Public License version 3 from Spigot, which
  inherits it from Bukkit and CraftBukkit. Contributors listed in Paper's `LICENSE.md` additionally
  release their Paper code under the MIT License; everyone else is presumed to have released their
  work under the GPL. A verbatim copy is in `licenses/Paper-LICENSE.md`.
- **Purpur** licenses its repository under the MIT License (`Copyright (c) 2019-2024 PurpurMC`).
  Purpur's patches modify Paper, so built Purpur servers are still combined with GPLv3 code. The MIT
  license requires that Purpur's copyright and permission notice is kept in all copies or substantial
  portions of its software; Cubiq ships it in `licenses/Purpur-LICENSE.txt` and inside the server jar.
- **Folia** is licensed under the GPLv3 (`PATCHES-LICENSE` in the Folia repository). Cubiq does
  not currently contain Folia code. If Folia code is ever ported, its origin and license must be
  recorded here and in `NOTICE`.
- **paperweight**, the Gradle plugin that applies the patches, is licensed under the LGPL 2.1. It is
  a build tool and is not part of the server that Cubiq distributes.
- **Paperclip**, the launcher that becomes the distributed jar, is MIT licensed and bundles
  `jbsdiff` (BSD-style license) and Apache Commons Compress (Apache License 2.0). Their license
  files are included by upstream tooling under `META-INF/license/` in the Paperclip jar.

## Cubiq's own license

The repository was created with the Apache License 2.0 (`LICENSE`). Cubiq keeps that license for the
work its contributors author themselves. The Apache License 2.0 is compatible with GPLv3 in one
direction: Apache-licensed code may be combined into a GPLv3 work, but GPLv3 code cannot be
relicensed under Apache 2.0. This leads to the following rules:

1. Files and classes written for Cubiq (for example everything under `studio.cubiq`) are
   Apache 2.0 licensed by their authors.
2. Cubiq's modifications of upstream files are Cubiq's contributions, but the modified files remain
   part of Paper's or Purpur's code. Modified GPLv3 files stay under the GPLv3; modified Purpur MIT
   files keep Purpur's notice.
3. A built Cubiq server combines all of the above with GPLv3 code and is therefore distributed under
   the GPLv3 as a whole.

Changing Cubiq's license is a decision for the copyright holders of Cubiq's code. Relicensing the
repository to GPLv3 would be possible for code Cubiq's contributors own, but it is not required for
compliance and has not been done.

## Obligations when distributing Cubiq builds

When distributing a Cubiq server jar (for example as a release or CI artifact):

- **License texts and notices travel with the binary.** The server jar contains
  `META-INF/license/` with Cubiq's `LICENSE` and `NOTICE`, the GPLv3 text, Paper's licensing
  statement and MIT text, and Purpur's MIT notice. The build configuration inherited from Purpur and
  Paper does not package these files into the server jar (only Mojang's own `META-INF/LICENSE` notice
  ends up there); Cubiq adds them in `cubiq-server/build.gradle.kts.patch`.
- **Corresponding source must be available.** The GPLv3 requires offering the complete corresponding
  source for distributed binaries. For Cubiq this is the Cubiq repository at the commit the build was
  made from (recorded in the jar manifest as `Git-Commit`) together with the pinned upstream commits
  (`purpurCommit` in `gradle.properties`, and the Paper commit pinned by that Purpur commit).
- **Modified files are marked.** Cubiq's changes to upstream files are marked with `// Cubiq`
  comments and are kept as patches, which documents what was changed.
- **The Apache 2.0 `NOTICE` must be kept.** Derivative works of Cubiq must include the attribution
  notices from `NOTICE` (Apache License 2.0, section 4(d)).

## Minecraft

Cubiq does not distribute the Minecraft server. The distributed Paperclip jar contains Cubiq's binary
patches and, on first start, downloads the vanilla server from Mojang and applies the patches locally.
Running a Minecraft server requires accepting the Minecraft EULA. The patch files in this repository
contain small context excerpts of the upstream code they modify, including decompiled Minecraft code,
in the same way as Paper's and Purpur's repositories.

Cubiq is not an official Minecraft product and is not approved by or associated with Mojang or
Microsoft. Cubiq must not use Mojang or Microsoft branding in a way that suggests endorsement.

## Attribution requirements

Cubiq Studio is credited in `NOTICE`, in the server jar manifest (`Specification-Vendor`), and in the
server brand reported to clients and plugins. The Apache License 2.0 already requires derivative works
to keep the `NOTICE` attributions, which is the OSI-compatible way to require attribution.

A stronger requirement, such as forcing forks to display Cubiq branding in-game, would be an
additional restriction. It would not be compatible with the Open Source Definition, and the GPLv3
(section 7) only permits a limited set of additional terms, such as preserving author attributions in
legal notices or requiring modified versions to be marked as changed. Any such requirement needs a
separate legal review before it is adopted.

## Checklist for maintainers

- Keep `licenses/` in sync with upstream when updating `purpurCommit`.
- Record the origin and license of any code copied from another project in `NOTICE` and here.
- Do not copy code from projects whose license is incompatible with the GPLv3.
- Keep upstream copyright headers and `// Paper` / `// Purpur` markers intact.
