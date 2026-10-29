#!/usr/bin/env python3
"""Collects the source code of the bundled libraries that Cubiq's releases attach.

Usage: scripts/release-sources.py <output directory>

The GPLv2, under which MySQL Connector/J is licensed, requires its source code to accompany the binary or a written
offer (section 3); unlike the GPLv3, it does not allow pointing to a server instead. Releases therefore attach the
source code of the libraries marked with "attachSourceToReleases" in scripts/library-notices.json: an archive of the
repository and commit recorded in their "source", fetched with git, which checks the commit's hash. Prints the paths of
the archives, one per line.
"""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

NOTICES = Path(__file__).resolve().parent / "library-notices.json"


def archive(module, library, output):
    """Fetches the source of a library at its recorded commit and writes it as <artifact>-<version>-source.tar.gz."""
    url, commit = library["source"].split()
    name = f"{module.split(':')[1]}-{library['version']}"
    target = output / f"{name}-source.tar.gz"
    with tempfile.TemporaryDirectory() as work:
        subprocess.run(["git", "init", "-q", work], check=True)
        subprocess.run(["git", "-C", work, "fetch", "-q", "--depth", "1", url, commit], check=True)
        fetched = subprocess.run(
            ["git", "-C", work, "rev-parse", "FETCH_HEAD"], check=True, capture_output=True, text=True
        ).stdout.strip()
        if fetched != commit:
            raise ValueError(f"{url} returned commit {fetched} instead of {commit}")
        subprocess.run(
            ["git", "-C", work, "archive", "--format=tar.gz", f"--prefix={name}/", "-o", str(target), commit], check=True
        )
    return target


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    output = Path(sys.argv[1]).resolve()
    output.mkdir(parents=True, exist_ok=True)
    libraries = json.loads(NOTICES.read_text(encoding="utf-8"))["libraries"]
    try:
        for module, library in sorted(libraries.items()):
            if library.get("attachSourceToReleases", False):
                print(archive(module, library, output))
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"release-sources: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
