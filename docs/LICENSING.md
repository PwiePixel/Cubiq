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
  files are included by upstream tooling under `META-INF/license/` in the Paperclip jar. The `NOTICE`
  file of Apache Commons Compress is not included.
- **CraftBukkit's license files.** Paper's `paper-server` directory contains `LICENCE.txt` (GPLv3) and
  `LGPL.txt` (LGPLv3), and `paper-api` contains `LICENCE.txt` (GPLv3). Paper's `LICENSE.md` does not explain
  which code the LGPLv3 text applies to. Cubiq ships both texts (`licenses/GPL-3.0.txt`, `licenses/LGPL-3.0.txt`).

## Third-party code inside the server

Some files in Paper carry their own license headers in addition to the GPLv3, for example the MIT-licensed
timings classes by Aikar (`co.aikar.*`, `CustomTimingsHandler`) and `JohnsonSimpleCycles`, which is derived from
JGraphT and licensed `EPL-2.0 OR LGPL-2.1-or-later`. Two of Paper's feature patches (Eigencraft and Alternate
Current redstone) record that the ported code was originally MIT licensed. Cubiq does not modify these files;
their headers must be kept.

A few upstream files carry notices that need a closer look (see the open questions below):

- `co.aikar.util.LoadingIntMap` in Paper's API, deprecated for removal, starts with "Copyright (c) 2015. Starlis
  LLC / dba Empire Minecraft" and "This source code is proprietary software and must not be redistributed
  without Starlis LLC's approval". Paper's API jar contains the compiled class, and so does Cubiq's.
- `io.papermc.paper.command.CommandUtil` contains a method marked `// Code from Mojang - copyright them`.
- Paper patches classes of Mojang's libraries (authlib, Brigadier, DataFixerUpper and logging) and of JLine. The
  patched classes are compiled into the server jar.
- `GS4QueryEvent` in Paper's API was adapted from Velocity's `ProxyQueryEvent`.

The Paperclip jar also contains the server's runtime libraries as separate jars, each under its own license.
[LIBRARIES.md](LIBRARIES.md) lists all of them with the licenses their Maven POMs declare, for example Configurate
(Apache 2.0), leafpile (GPLv3), Rhino (MPL 2.0), upnp4j (LGPL 3.0, added by Purpur) and MySQL Connector/J (GPLv2
with the Universal FOSS Exception). Some come in as dependencies of other libraries, for example Eclipse Sisu Inject
(EPL 1.0, through the Maven model builder used by Paper's API) and protobuf-java (BSD-3-Clause, through MySQL
Connector/J). `scripts/library-licenses.py` generates the list from the Paperclip jar, and CI fails when a library
has a license that `scripts/library-licenses.json` does not cover or when the list is out of date. The libraries of
Mojang's server jar, which Paperclip downloads from Mojang, are listed separately; Cubiq does not distribute them.

Four bundled libraries declare no license in their POMs or jars. Their licenses were looked up in the projects'
repositories (the commits are recorded in `scripts/library-licenses.json`): spark is licensed under the GPLv3 and its
API under the MIT License, and Velocity's native module falls under Velocity's GPLv3. Its native libraries contain
libdeflate (MIT) and use the operating system's OpenSSL, which is not bundled. The fourth, the BungeeCord chat API,
is an open question below.

Open questions that need an answer before the first release:

- Whether Paper is licensed GPLv3-only or GPLv3-or-later. Paper's `LICENSE.md` says "version 3"; some
  Bukkit-era file headers say "or (at your option) any later version".
- The licenses of the Moonrise and DataConverter code included in Paper, whose patches only link to their
  repositories.
- The license of the logo images: the server GUI currently shows the `logo.png` that Purpur ships.
- Whether `co.aikar.util.LoadingIntMap` may be redistributed despite its "proprietary" header, or whether Cubiq
  should remove it. Removing it could break plugins that still use the deprecated class.
- Whether bundling Eclipse Sisu Inject (EPL 1.0, which the FSF lists as incompatible with the GPL) as a separate
  library jar is acceptable. Paper bundles the same library.
- How to provide the attribution notices of Apache Commons Compress, whose `NOTICE` file the Paperclip jar does
  not contain.
- Whether the Universal FOSS Exception of MySQL Connector/J (GPLv2 only) covers its distribution with the server.
  Paper bundles the same library.
- The license of `bungeecord-chat`, the BungeeCord chat API that the Spigot and Paper APIs expose. BungeeCord's
  license, which covers its chat module, is the BSD 3-Clause License with an additional clause: "You may not use
  the software for commercial software hosting services without written permission from the author." Such a
  restriction is not part of an open source license, and the GPLv3 does not allow further restrictions on the
  combined work. Paper bundles the same library.
- How to ship the notices of the bundled libraries: 44 of the 69 jars in the Paperclip jar contain no license or
  notice file, although licenses such as MIT and BSD require their notices to accompany binary copies. This includes
  libdeflate in the native libraries of `velocity-native`. A file with the license texts and copyright notices of
  all bundled libraries would close this gap.

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
  `META-INF/license/` with Cubiq's `LICENSE` and `NOTICE`, the GPLv3 and LGPLv3 texts, Paper's
  licensing statement and MIT text, and Purpur's MIT notice. The build configuration inherited from
  Purpur and Paper does not package these files into the server jar (only Mojang's own
  `META-INF/LICENSE` notice, which refers to the Minecraft EULA, ends up there); Cubiq adds them in
  `cubiq-server/build.gradle.kts.patch`. The distributed Paperclip jar contains the server jar only as
  a binary patch, so Cubiq adds the same files to the Paperclip jar itself, next to the license files
  of Paperclip and the libraries it includes (jbsdiff and Apache Commons Compress). The server jar
  Paperclip creates on first start (`versions/<version>/cubiq-<version>.jar`) contains them as well.
  `scripts/check-distribution.sh` verifies both jars in CI.
- **Corresponding source must be available.** The GPLv3 requires offering the complete corresponding
  source for distributed binaries. For Cubiq this is the Cubiq repository at the commit the build was
  made from (recorded in the jar manifest as `Git-Commit`) together with the pinned upstream commits
  (`purpurCommit` in `gradle.properties`, and the Paper commit pinned by that Purpur commit). On
  every push, CI builds the jars a second time without reusing any build output, checks that the
  server jar and the Paperclip jar are identical to the first build and reports the SHA-256 of the
  Paperclip jar. Whether a build on another machine produces the same jar has not been verified
  yet. Source archives and a written source offer for releases are not set up yet; they are part of
  the release process on the roadmap.
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

- Keep `licenses/` in sync with upstream when updating `purpurCommit`, and regenerate
  [LIBRARIES.md](LIBRARIES.md) with `scripts/library-licenses.py`. A new license name or license needs an entry in
  `scripts/library-licenses.json`, reviewed against the GPLv3.
- Record the origin and license of any code copied from another project in `NOTICE` and here.
- Do not copy code from projects whose license is incompatible with the GPLv3.
- Keep upstream copyright headers and `// Paper` / `// Purpur` markers intact.
