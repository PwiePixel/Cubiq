#!/usr/bin/env python3
"""Writes the notes of a Cubiq release or snapshot.

Usage: scripts/release-notes.py --version <version> --previous <tag or ""> --jar <jar> --minecraft <version>
           --purpur <commit> --paper <commit> [--repository <owner/name>] [--commit <commit>]
           [--sources <archive>...] [--snapshot]

Prints the notes in Markdown: what the release is, the file to download with its SHA-256, the upstream commits it is
based on, the changes since the previous release (the subjects of the commits, without merge commits, from the git
history of the current directory), the source code archives attached for bundled libraries, and the license and EULA
notices. With --snapshot, the notes describe the snapshot, the newest development build, and --version is not used.
Runs in the repository; needs its history back to the previous release.
"""

import argparse
import hashlib
import re
import subprocess
import sys
from pathlib import Path

SOURCE_ARCHIVE = re.compile(r"^(?P<artifact>.+)-(?P<version>[^-]+)-source\.tar\.gz$")


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
    repository = f"https://github.com/{arguments.repository}"
    if arguments.snapshot:
        # The tree of the commit, which stays available after the snapshot has been replaced
        ref = commit
        lines = [
            f"The newest development build of Cubiq for Minecraft {arguments.minecraft}, built from commit "
            f"{commit[:7]}.",
            "",
            "This is a snapshot, not a release: every build of a development branch that passes all of Cubiq's checks "
            "replaces it, so it can contain changes that are not finished. Use the numbered releases for servers you care "
            "about, and back up your worlds before you try it.",
        ]
        source = f"commit `{commit[:7]}` of this repository"
    else:
        ref = "v" + arguments.version
        lines = [
            f"Cubiq {ref} for Minecraft {arguments.minecraft}, built from commit {commit[:7]}.",
            "",
            "This is a development release. Every change on `main` that passes all of Cubiq's checks (build, tests, "
            "test servers, license and distribution checks, reproducible build) is published as a numbered pre-release. "
            "It has not been tested for production use; back up your worlds before you try it.",
        ]
        source = f"the tag `{ref}` of this repository"
    lines += [
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
    elif arguments.snapshot:
        lines.append("**Changes:** there is no release yet, so these are all commits:")
    else:
        lines.append("**Changes:** this is the first release, with these commits:")
    lines.append("")
    if commits:
        lines.extend(f"- {subject} ({short})" for short, subject in commits)
    else:
        lines.append("- None.")
    lines += [
        "",
        "**License:** the server as a whole is distributed under the GNU General Public License, version 3 only; "
        "Cubiq's own code is licensed under the Apache License 2.0. The license texts and notices are in the jar under "
        f"`META-INF/license/` and in [`licenses/`]({repository}/tree/{ref}/licenses). The source code of this build "
        f"is {source} together with the Purpur and Paper commits above; see "
        f"[LICENSING.md]({repository}/blob/{ref}/docs/LICENSING.md).",
    ]
    for archive in arguments.sources:
        match = SOURCE_ARCHIVE.match(Path(archive).name)
        if not match:
            raise ValueError(f"not a source archive of release-sources.py: {archive}")
        lines += [
            "",
            f"**Source code of {match['artifact']} {match['version']}:** `{Path(archive).name}`, attached because the "
            "license of this bundled library, the GPLv2, requires its source code to accompany the server.",
        ]
    lines += [
        "",
        "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.",
    ]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description="Writes the notes of a Cubiq release or snapshot.")
    parser.add_argument("--version", default="")
    parser.add_argument("--previous", required=True)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--purpur", required=True)
    parser.add_argument("--paper", required=True)
    parser.add_argument("--repository", default="PwiePixel/Cubiq")
    parser.add_argument("--commit", default="HEAD")
    parser.add_argument("--sources", nargs="*", default=[], help="source code archives attached for bundled libraries")
    parser.add_argument("--snapshot", action="store_true", help="describe the snapshot instead of a release")
    arguments = parser.parse_args()
    required = ("minecraft", "purpur", "paper") if arguments.snapshot else ("version", "minecraft", "purpur", "paper")
    for name in required:
        if not getattr(arguments, name).strip():
            print(f"release-notes: --{name} is empty", file=sys.stderr)
            return 1
    try:
        sys.stdout.write(notes(arguments))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"release-notes: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
