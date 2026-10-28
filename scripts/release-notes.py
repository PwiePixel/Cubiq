#!/usr/bin/env python3
"""Writes the notes of a Cubiq release.

Usage: scripts/release-notes.py --version <version> --previous <tag or ""> --jar <jar> --minecraft <version>
           --purpur <commit> --paper <commit> [--repository <owner/name>] [--commit <commit>]

Prints the notes in Markdown: what the release is, the file to download with its SHA-256, the upstream commits it is
based on, the changes since the previous release (the subjects of the commits, without merge commits, from the git
history of the current directory), and the license and EULA notices. Runs in the repository; needs its history back
to the previous release.
"""

import argparse
import hashlib
import subprocess
import sys
from pathlib import Path


def changes(previous, commit):
    """Returns the subjects and short hashes of the commits since the previous release, newest first."""
    revisions = f"{previous}..{commit}" if previous else commit
    output = subprocess.run(
        ["git", "log", "--no-merges", "--format=%h%x09%s", revisions],
        check=True, capture_output=True, text=True,
    ).stdout
    return [line.split("\t", 1) for line in output.splitlines() if line]


def notes(arguments):
    jar = Path(arguments.jar)
    sha256 = hashlib.sha256(jar.read_bytes()).hexdigest()
    commit = subprocess.run(["git", "rev-parse", arguments.commit], check=True, capture_output=True, text=True).stdout.strip()
    tag = "v" + arguments.version
    repository = f"https://github.com/{arguments.repository}"
    lines = [
        f"Cubiq {tag} for Minecraft {arguments.minecraft}, built from commit {commit[:7]}.",
        "",
        "This is a development release. Every change on `main` that passes all of Cubiq's checks (build, tests, test "
        "servers, license and distribution checks, reproducible build) is published as a numbered pre-release. It has "
        "not been tested for production use; back up your worlds before you try it.",
        "",
        f"**Download:** `{jar.name}` (SHA-256 `{sha256}`). Start it with Java 25, for example "
        f"`java -jar {jar.name} --nogui`. On the first start, it downloads the Minecraft {arguments.minecraft} server "
        "from Mojang and applies Cubiq's patch to it. Running a Minecraft server requires accepting the "
        "[Minecraft EULA](https://aka.ms/MinecraftEULA).",
        "",
        f"**Based on:** Purpur [`{arguments.purpur[:7]}`](https://github.com/PurpurMC/Purpur/commit/{arguments.purpur}) "
        f"and Paper [`{arguments.paper[:7]}`](https://github.com/PaperMC/Paper/commit/{arguments.paper}).",
        "",
    ]
    commits = changes(arguments.previous, commit)
    if arguments.previous:
        lines.append(f"**Changes since {arguments.previous}:**")
    else:
        lines.append("**Changes:** this is the first release, with these commits:")
    lines.append("")
    if commits:
        lines.extend(f"- {subject} ({short})" for short, subject in commits)
    else:
        lines.append("- None.")
    lines += [
        "",
        "**License:** the server as a whole is distributed under the GNU General Public License version 3; Cubiq's own "
        "code is licensed under the Apache License 2.0. The license texts and notices are in the jar under "
        f"`META-INF/license/` and in [`licenses/`]({repository}/tree/{tag}/licenses). The source code of this release "
        f"is the tag `{tag}` of this repository together with the Purpur and Paper commits above; see "
        f"[LICENSING.md]({repository}/blob/{tag}/docs/LICENSING.md).",
        "",
        "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.",
    ]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description="Writes the notes of a Cubiq release.")
    parser.add_argument("--version", required=True)
    parser.add_argument("--previous", required=True)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--purpur", required=True)
    parser.add_argument("--paper", required=True)
    parser.add_argument("--repository", default="PwiePixel/Cubiq")
    parser.add_argument("--commit", default="HEAD")
    arguments = parser.parse_args()
    for name in ("version", "minecraft", "purpur", "paper"):
        if not getattr(arguments, name).strip():
            print(f"release-notes: --{name} is empty", file=sys.stderr)
            return 1
    try:
        sys.stdout.write(notes(arguments))
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"release-notes: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
