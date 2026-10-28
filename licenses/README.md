# Third-party licenses

Verbatim copies of the license texts that apply to code Cubiq is built from.
They are packaged into the server jar under `META-INF/license/`.

| File | Source | Notes |
| --- | --- | --- |
| `GPL-3.0.txt` | GNU General Public License v3 (canonical FSF text) | Referenced by Paper's `LICENSE.md` as `licenses/GPL.md` |
| `LGPL-3.0.txt` | `paper-server/LGPL.txt` of Paper (inherited from CraftBukkit) | GNU Lesser General Public License v3; shipped by upstream next to the GPLv3 text |
| `Paper-LICENSE.md` | [`LICENSE.md` of Paper](https://github.com/PaperMC/Paper/blob/main/LICENSE.md) | Lists the contributors who also release their Paper code under MIT |
| `Paper-MIT.md` | [`licenses/MIT.md` of Paper](https://github.com/PaperMC/Paper/blob/main/licenses/MIT.md) | Referenced by Paper's `LICENSE.md` as `licenses/MIT.md` |
| `Purpur-LICENSE.txt` | [`LICENSE` of Purpur](https://github.com/PurpurMC/Purpur/blob/HEAD/LICENSE) | MIT, Copyright (c) 2019-2024 PurpurMC |

The license texts and notices of code of other projects whose own files do not carry them all are in
`third-party/`, one folder per project, as the projects publish them: those of the bundled libraries whose jars
contain no license or notice file or lack some of their notices, and those of code that is compiled into the server
jar or the Paperclip jar itself. `THIRD-PARTY-NOTICES.md` lists which of them apply to which library or code, with the
source of each file, usually a commit of the project's repository. Do not edit these files: they must stay
byte-identical to their sources, which `scripts/library-notices.json` records and CI checks.
`THIRD-PARTY-NOTICES.md` is generated with `scripts/library-licenses.py --notices`.

Cubiq's own license is in the repository root (`LICENSE`, Apache License 2.0), together with the `NOTICE` file.
See [`docs/LICENSING.md`](../docs/LICENSING.md) for how these licenses fit together.

When updating the upstream pins, check whether these files changed upstream and refresh the copies.
