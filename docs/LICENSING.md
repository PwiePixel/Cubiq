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
| Built Cubiq server (combined work) | GNU GPL v3 only | `licenses/GPL-3.0.txt` |

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
  file of Apache Commons Compress is not; Cubiq adds it (`third-party/commons-compress-1.5/NOTICE.txt`).
- **CraftBukkit's license files.** Paper's `paper-server` directory contains `LICENCE.txt` (GPLv3) and
  `LGPL.txt` (LGPLv3), and `paper-api` contains `LICENCE.txt` (GPLv3). Paper's `LICENSE.md` does not explain
  which code the LGPLv3 text applies to. Cubiq ships both texts (`licenses/GPL-3.0.txt`, `licenses/LGPL-3.0.txt`).

## Third-party code inside the server

Some files in Paper carry their own license headers in addition to the GPLv3, for example the MIT-licensed
timings classes by Aikar (`co.aikar.*`, `CustomTimingsHandler`) and `JohnsonSimpleCycles`, which is derived from
JGraphT and licensed `EPL-2.0 OR LGPL-2.1-or-later`. Cubiq does not modify these files; their headers must be kept.

Paper also compiles code of other projects into the server jar. `licenses/THIRD-PARTY-NOTICES.md` lists the license
texts and notices of this code under "Code compiled into the server jar and the Paperclip jar":

- Paper patches classes of Mojang's libraries Brigadier and DataFixerUpper, which Mojang publishes under the MIT
  license (Copyright (c) Microsoft Corporation), and JLine's `ShutdownHooks`. The patched classes are compiled into
  the server jar.
- Paper's feature patch that adds explicit flushing to Log4j's `AsyncAppender` compiles modified copies of two Log4j
  classes (Apache License 2.0) into the server jar.
- Paper's feature patch with the Alternate Current redstone implementation, written by its author, records that the
  code was originally MIT licensed; Alternate Current's license file is included. The feature patch with the
  Eigencraft redstone implementation, written by its author theosib, records the same, but names no project or
  license text, and its file has no license header.

A few upstream files carry notices that needed a closer look (see the decisions below):

- `co.aikar.util.LoadingIntMap` in Paper's API, deprecated for removal, starts with "Copyright (c) 2015. Starlis
  LLC / dba Empire Minecraft" and "This source code is proprietary software and must not be redistributed
  without Starlis LLC's approval". Paper's API jar contains the compiled class, and so does Cubiq's.
- `io.papermc.paper.command.CommandUtil` contains a method marked `// Code from Mojang - copyright them`.
- Paper patches classes of Mojang's authlib and logging libraries, for which Mojang publishes no license, and
  compiles them into the server jar.
- `GS4QueryEvent` in Paper's API was adapted from Velocity's `ProxyQueryEvent`.

The Paperclip jar also contains the server's runtime libraries as separate jars, each under its own license.
[LIBRARIES.md](LIBRARIES.md) lists all of them with the licenses their Maven POMs declare and, for the libraries in
`scripts/library-notices.json`, the licenses determined from their source, for example Configurate (Apache 2.0),
leafpile (GPLv3), Rhino (MPL 2.0), upnp4j (LGPL 3.0, added by Purpur) and MySQL Connector/J (GPLv2 with the Universal
FOSS Exception). Some come in as dependencies of other libraries, for example protobuf-java (BSD-3-Clause, through MySQL
Connector/J).
`scripts/library-licenses.py` generates the list from the Paperclip jar, and CI fails when a library has a license
that `scripts/library-licenses.json` does not cover or when the list is out of date. The libraries of Mojang's server
jar, which Paperclip downloads from Mojang, are listed separately; Cubiq does not distribute them.

### License texts and notices of the libraries

Of the 68 jars in the Paperclip jar, 67 are libraries of other projects, and 43 of these contain no LICENSE, NOTICE
or COPYING file, although licenses such as MIT and BSD require their notices to accompany binary copies, and the
Apache License 2.0 requires its text and the project's `NOTICE` file. The jars of other libraries contain license files that lack notices that apply to them: for
example the BSD notices of the startup code in the native libraries of sqlite-jdbc, the Apache License 1.1 headers of
ten files of plexus-utils, David M. Gay's notice on the number conversion code of Rhino, and the NOTICE of Apache
Commons Logging, from which jcl-over-slf4j is derived. Cubiq ships these texts and notices itself:
`licenses/third-party/` contains them, and `licenses/THIRD-PARTY-NOTICES.md` lists, for each library, its license, its
source, its copyright notices and the files that apply. Both are in the server jar and the Paperclip jar under
`META-INF/license/`. The same overview covers the code of other projects that is compiled into the server jar or the
Paperclip jar itself (see [Third-party code inside the server](#third-party-code-inside-the-server) and Apache Commons
Compress in Paperclip).

The files were collected from each library's source at the bundled version, including the licenses of code of other
projects that the jars contain, for example the relocated libraries in spark, libdeflate in the native libraries of
`velocity-native`, WaifUPnP in upnp4j, a fork of SnakeYAML in Configurate, fastutil code in leafpile, and the toolchain
code in the native libraries of `jline-native`, sqlite-jdbc and spark. For javax.inject and plexus-interpolation, whose
projects have no license file, the Apache License 2.0 text was taken from apache.org. `scripts/library-notices.json`
records the source (repository, commit and path, or URL) and the SHA-256 of every file; each file was fetched again from
its source and compared byte for byte before it was added. `scripts/library-licenses.py` fails in CI when a bundled
library without notices in its jar has no entry for its bundled version, or when a file differs from the recorded one,
and `scripts/check-distribution.sh` checks that both jars contain every file unchanged. Whether the license files in a
library's jar are complete is not checked automatically; libraries were found to need an entry by reviewing their jars
and sources. The licenses determined this way are more precise than the ones the POMs declare, and
[LIBRARIES.md](LIBRARIES.md) uses them for these libraries.

The libraries licensed under the GPL or the LGPL (leafpile, spark, `velocity-native`, upnp4j and MySQL Connector/J) and
under the MPL (Rhino) are distributed as separate jars; their source code is available from the repositories and
commits named in `THIRD-PARTY-NOTICES.md`.

### Decisions on the licensing questions

Collecting the notices raised the questions below. The project owner decided them on 2026-10-29. Like the rest of this
document, they are not legal advice. Where a decision needs a change to the build that has not been made yet, the item
says so.

- **Version of the GPL.** Cubiq distributes the server under version 3 of the GPL only. Paper's `LICENSE.md` says
  "version 3"; the Bukkit-era files whose headers say "or (at your option) any later version" may be distributed
  under version 3 as well.
- **Moonrise and DataConverter**, whose code Paper includes, are licensed under the GPLv3, which their repositories
  state: `LICENSE.md` of [Tuinity/Moonrise](https://github.com/Tuinity/Moonrise) (unchanged since its first commit,
  7c753ab) and `LICENSE` of [PaperMC/DataConverter](https://github.com/PaperMC/DataConverter), which changed from the
  LGPLv3 to the GPLv3 in commit 3138743 (2024-04-20), checked at commits 764a70f and cc6b1f3. Paper's patches do not
  name the commits they are based on.
- **`co.aikar.util.LoadingIntMap`** stays despite its "proprietary" header, as in Paper, because removing it could break
  plugins that still use the deprecated class.
- **The patched classes of Mojang's authlib and logging libraries** are distributed in the server as Paper distributes
  them.
- **The patch in the Paperclip jar** is distributed as it is, the same way as Paper and Purpur distribute theirs.
- **Eclipse Sisu Inject** (EPL 1.0; its later versions are under the EPL 2.0 without a designated secondary license,
  which the FSF also lists as incompatible with the GPL) is left out of the server. The bundled Maven classes that
  resolve the libraries of plugins only name it in annotations (`@org.eclipse.sisu.Nullable` and `@Typed`), which the
  JVM ignores when their classes are missing, and a server without it loaded a plugin's library from its `plugin.yml`.
  Paper bundles it.
- **MySQL Connector/J** stays under its own license, the GPLv2 with the Universal FOSS Exception, which allows
  distributing it together with the GPLv3 server. Because the GPLv2 (section 3) requires its source code to accompany
  the binary or a written offer, every release and the snapshot attach the source code of the bundled version,
  `mysql-connector-j-<version>-source.tar.gz`, made by `scripts/release-sources.py` from the commit of Oracle's
  repository that the jar records as its source; the artifact of each CI run contains it next to the jar. CI checks
  that the commit recorded in `scripts/library-notices.json` is the one in the jar's `INFO_SRC`, and that every
  library under the GPLv2 or the LGPLv2.1 only has its source attached.
- **`bungeecord-chat`** is distributed as in Paper. Its additional clause does not only concern Cubiq: it applies to
  everyone who uses the software for commercial software hosting services, for example hosting providers that offer
  Cubiq servers.
- **The Apache License 1.1 and the Indiana University Extreme! Lab Software License 1.1.1** in plexus-interpolation and
  plexus-utils: the files are distributed as in Paper; the project owner considers this acceptable.
- **The 32-bit (i386) macOS native library of `jline-native`**, which contains Apple's startup code (Csu) under the
  APSL 2.0, is left out: no Java 25 runtime exists for 32-bit macOS, so the server never loads it. The build bundles
  the jar without `org/jline/nativ/Mac/x86/` (see `cubiq-server/build.gradle.kts.patch`), and the build fails if a
  new version of jline-native no longer contains that directory, so that the decision is reviewed.
- **Minecraft's English language strings in `bungeecord-chat`** (`mojang-translations`) are distributed as in Paper.
- **Logo.** The server GUI shows Cubiq's logo, which is part of Cubiq's own work under the Apache License 2.0;
  Purpur's logo is no longer in the server. The patch that replaces it, `0001-Cubiq-logo.patch` in
  `cubiq-server/paper-patches/features/`, still contains Purpur's logo, because a binary patch of git holds both
  versions of the file. The license grants no right to use the logo or the name Cubiq as a trademark (Apache License
  2.0, section 6).
- **Source code of releases.** Each release names the tag of this repository it was built from and the Purpur and
  Paper commits, and GitHub offers archives of the tag. The GPLv3- and LGPLv3-licensed libraries are referred to by the
  repositories and commits in `THIRD-PARTY-NOTICES.md` (GPLv3 section 6(d)). Releases attach no further source
  archives, except the source code of MySQL Connector/J, see above. The same applies to the snapshot, the newest
  development build, whose notes name the commit it was built from.

A license itself cannot settle these questions: Cubiq can only license its own work, and the code of other projects
stays under the licenses its authors chose. Where code under a license that the FSF lists as incompatible with the GPL
can be left out without losing anything, leaving it out is the solution.

The statements about the FSF's view of licenses in this document and in `scripts/library-licenses.json` were checked
against the FSF's [list of licenses](https://www.gnu.org/licenses/license-list.html) on 2026-10-28: it lists the Zope
Public License 2.0 and 2.1 as compatible with the GPL, and the Apache License 1.1, the Apple Public Source License 2.0
and the Eclipse Public License 1.0 as incompatible.

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
  Paperclip creates on first start (`versions/<version>/cubiq-<version>.jar`) contains them as well, together with the
  license texts and notices of the bundled libraries (see
  [License texts and notices of the libraries](#license-texts-and-notices-of-the-libraries)).
  `scripts/check-distribution.sh` verifies both jars in CI.
- **Corresponding source must be available.** The GPLv3 requires offering the complete corresponding
  source for distributed binaries. For Cubiq this is the Cubiq repository at the commit the build was
  made from (recorded in the jar manifest as `Git-Commit`) together with the pinned upstream commits
  (`purpurCommit` in `gradle.properties`, and the Paper commit pinned by that Purpur commit). On
  every push, CI builds the jars a second time without reusing any build output, checks that the
  server jar and the Paperclip jar are identical to the first build and reports the SHA-256 of the
  Paperclip jar. Builds of two commits on another machine, with a different JDK and operating system,
  produced the same Paperclip jar as CI when they used the same branch name and build time
  (see [CONTRIBUTING.md](CONTRIBUTING.md)). The notes of each [release](CONTRIBUTING.md#releases), next to the jar,
  name the tag of this repository that it was built from and link the Purpur and Paper commits; GitHub offers
  archives of the tag, which contain Cubiq's patches but not the upstream sources. The GPLv3 (section 6(d)) allows the
  corresponding source to be on another server if clear directions next to the binary say where to find it, but the
  distributor stays obliged to keep it available. The project owner decided that releases attach no archive with the
  upstream sources; they attach the source code of MySQL Connector/J, whose license, the GPLv2, has no such provision
  (see the decisions above).
- **Modified files are marked.** Cubiq's changes to upstream files are marked with `// Cubiq`
  comments and are kept as patches, which documents what was changed.
- **The Apache 2.0 `NOTICE` must be kept.** Derivative works of Cubiq must include the attribution
  notices from `NOTICE` (Apache License 2.0, section 4(d)).

## Minecraft

Cubiq does not distribute Mojang's server jar or the libraries it contains. The distributed Paperclip jar contains a
binary patch (bsdiff) and, on first start, downloads the vanilla server from Mojang and applies the patch locally. The
patch is not limited to differences: it contains the classes that Paper, Purpur and Cubiq change or add, compiled, in
large part as literal bytes, including modified Minecraft classes and the patched classes of Mojang's libraries (see
the decisions above). `scripts/check-distribution.sh` checks that the Paperclip jar contains no Minecraft or Mojang
classes and no Mojang libraries as entries of their own, and the server only as a patch; it does not inspect the
content of the patch. Running a Minecraft server requires accepting the Minecraft EULA. The patch files in this
repository contain small context excerpts of the upstream code they modify, including decompiled Minecraft code, in the
same way as Paper's and Purpur's repositories.

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
- When a bundled library is added or changes its version, check that its jar contains the license texts and notices
  that apply to all the code in it, including the code of other projects it contains and its native libraries. If it
  does not, collect them from its source at that version and update `licenses/third-party/` and
  `scripts/library-notices.json` as described in [CONTRIBUTING.md](CONTRIBUTING.md); for a library whose jar contains
  no license or notice file, or that has an entry already, CI fails until then. Do the same for code of other projects
  that the server jar or the Paperclip jar contains (`embedded` in `scripts/library-notices.json`).
- Record the origin and license of any code copied from another project in `NOTICE` and here.
- Do not copy code from projects whose license is incompatible with the GPLv3.
- Keep upstream copyright headers and `// Paper` / `// Purpur` markers intact.
