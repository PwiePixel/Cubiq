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

Cubiq's own license is in the repository root (`LICENSE`, Apache License 2.0), together with the `NOTICE` file.
See [`docs/LICENSING.md`](../docs/LICENSING.md) for how these licenses fit together.

When updating the upstream pins, check whether these files changed upstream and refresh the copies.
