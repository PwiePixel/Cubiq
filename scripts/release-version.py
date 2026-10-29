#!/usr/bin/env python3
"""Determines the version of the release of a Cubiq commit.

Usage: scripts/release-version.py [--commit <commit>] [<gradle.properties>]
       scripts/release-version.py --self-test

Reads the release tags (vMAJOR.MINOR.PATCH) of the git repository in the current directory and MAJOR.MINOR of the
next release from the cubiqVersion property in gradle.properties, and prints:

    version=0.0.1     the version of the release
    tag=v0.0.1        its tag
    build=1           its build number: the number of releases up to and including this one
    previous=         the tag of the release before it, empty for the first release
    released=false    whether the commit is released already
    contained=        the first release that contains the commit, if the commit is part of a release but not released
                      itself; version, tag, build and previous are empty then
    behind=           the latest release, if the commit does not contain it

A commit is released only once. If a release tag points at the commit (HEAD by default), the script prints that
release with released=true, so that building the commit again reproduces the release. If a release contains the
commit, for example when an older run of main is started again after a newer commit was released, the commit gets no
release of its own. Otherwise the script prints the next release: its patch number continues from the highest release
of MAJOR.MINOR, and the first release of MAJOR.MINOR has the patch number 0, except for 0.0, whose first release is
0.0.1. The script fails if MAJOR.MINOR is lower than the latest release, so that versions never go backwards. Tags that
are no release tags, such as v1 or v0.0.1-rc1, are ignored. Fetch the tags first (git fetch --tags); CI clones the
repository with all of them.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

RELEASE_TAG = re.compile(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
VERSION_LINE = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")


def releases(tags):
    """Returns the versions of the release tags among the tags, sorted."""
    found = set()
    for tag in tags:
        match = RELEASE_TAG.match(tag.strip())
        if match:
            found.add(tuple(int(number) for number in match.groups()))
    return sorted(found)


def version_line(properties):
    """Returns MAJOR.MINOR from the cubiqVersion property of a gradle.properties file."""
    for line in properties.splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == "cubiqVersion":
            match = VERSION_LINE.match(value.strip())
            if not match:
                raise ValueError(f"cubiqVersion must be MAJOR.MINOR, for example 0.1, not '{value.strip()}'")
            return int(match.group(1)), int(match.group(2))
    raise ValueError("gradle.properties has no cubiqVersion")


def tag(version):
    return "v%d.%d.%d" % version


def describe(version, existing, released, behind=None):
    """Returns the version, tag, build number and previous tag of a release, given the releases before it."""
    before = [release for release in existing if release < version]
    text = "%d.%d.%d" % version
    return {
        "version": text,
        "tag": "v" + text,
        "build": str(len(before) + 1),
        "previous": tag(before[-1]) if before else "",
        "released": "true" if released else "false",
        "contained": "",
        "behind": tag(behind) if behind else "",
    }


def release(line, existing, at_commit, containing, merged):
    """Returns the release of the commit: the existing release if a release tag points at it, none if a release
    contains it, else the next one.

    existing, at_commit, containing and merged are the versions of all release tags, of those that point at the commit,
    of those whose commits contain the commit and of those that the commit contains. line is a function that returns
    MAJOR.MINOR of the next release; it is only called for the next release.
    """
    if at_commit:
        return describe(max(at_commit), existing, True)
    if containing:
        result = dict.fromkeys(("version", "tag", "build", "previous", "contained", "behind"), "")
        result.update(released="false", contained=tag(min(containing)))
        return result
    major, minor = line()
    if existing and (major, minor) < existing[-1][:2]:
        raise ValueError(f"cubiqVersion {major}.{minor} is lower than the latest release {tag(existing[-1])}")
    patches = [version[2] for version in existing if version[:2] == (major, minor)]
    if patches:
        patch = max(patches) + 1
    else:
        patch = 1 if (major, minor) == (0, 0) else 0
    behind = existing[-1] if existing and existing[-1] not in merged else None
    return describe((major, minor, patch), existing, False, behind)


def git_tags(*arguments):
    """Returns the tags starting with v, optionally only some of them, for example those that point at a commit."""
    return subprocess.run(
        ["git", "tag", "--list", "v*", *arguments], check=True, capture_output=True, text=True
    ).stdout.splitlines()


def self_test():
    # The tags of the repository, those that point at the commit, those that contain it and those that it contains
    cases = [
        ("the first release", (0, 0), [], [], [], [],
         {"version": "0.0.1", "build": "1", "previous": "", "released": "false", "contained": "", "behind": ""}),
        ("the next patch", (0, 0), ["v0.0.1", "v0.0.2"], [], [], ["v0.0.1", "v0.0.2"],
         {"version": "0.0.3", "build": "3", "previous": "v0.0.2", "released": "false", "contained": "", "behind": ""}),
        ("a new minor version", (0, 1), ["v0.0.1", "v0.0.2", "v0.0.3"], [], [], ["v0.0.1", "v0.0.2", "v0.0.3"],
         {"version": "0.1.0", "build": "4", "previous": "v0.0.3", "released": "false", "behind": ""}),
        ("a new major version", (1, 0), ["v0.1.4"], [], [], ["v0.1.4"],
         {"version": "1.0.0", "build": "2", "previous": "v0.1.4", "released": "false", "behind": ""}),
        ("other tags", (0, 0), ["v0.0.1", "v0.0.10", "v0.0.2-rc1", "v1", "v0.01.0", "nightly", ""], ["v1"], ["v1"],
         ["v0.0.1", "v0.0.10", "v1"],
         {"version": "0.0.11", "build": "3", "previous": "v0.0.10", "released": "false", "contained": "", "behind": ""}),
        ("a released commit", (0, 0), ["v0.0.1", "v0.0.2", "v0.0.3"], ["v0.0.2"], ["v0.0.2", "v0.0.3"],
         ["v0.0.1", "v0.0.2"],
         {"version": "0.0.2", "build": "2", "previous": "v0.0.1", "released": "true", "contained": ""}),
        ("the first release, released", (0, 0), ["v0.0.1"], ["v0.0.1"], ["v0.0.1"], ["v0.0.1"],
         {"version": "0.0.1", "build": "1", "previous": "", "released": "true", "contained": ""}),
        ("a released commit of an older version line", (0, 0), ["v0.0.3", "v0.1.0"], ["v0.0.3"], ["v0.0.3", "v0.1.0"],
         ["v0.0.3"],
         {"version": "0.0.3", "build": "1", "previous": "", "released": "true", "contained": ""}),
        ("a commit that a release contains", (0, 0), ["v0.0.1", "v0.0.2", "v0.0.3"], [], ["v0.0.2", "v0.0.3"],
         ["v0.0.1"],
         {"version": "", "tag": "", "build": "", "previous": "", "released": "false", "contained": "v0.0.2"}),
        ("a commit that a release of a higher version line contains", (0, 0), ["v0.0.1", "v0.1.0"], [], ["v0.1.0"],
         ["v0.0.1"],
         {"version": "", "released": "false", "contained": "v0.1.0", "behind": ""}),
        ("a commit without the latest release", (0, 0), ["v0.0.1", "v0.0.2"], [], [], ["v0.0.1"],
         {"version": "0.0.3", "build": "3", "previous": "v0.0.2", "released": "false", "contained": "",
          "behind": "v0.0.2"}),
        ("a commit without any release", (0, 0), ["v0.0.1"], [], [], [],
         {"version": "0.0.2", "previous": "v0.0.1", "released": "false", "behind": "v0.0.1"}),
    ]
    failures = 0
    for name, line, tags, at_commit, containing, merged, expected in cases:
        actual = release(lambda: line, releases(tags), releases(at_commit), releases(containing), releases(merged))
        expected_tag = "v" + expected["version"] if expected["version"] else ""
        if any(actual[key] != value for key, value in expected.items()) or actual["tag"] != expected_tag:
            print(f"FAILED: {name}: expected {expected}, got {actual}", file=sys.stderr)
            failures += 1
    for name, line, tags in [("a lower version line", (0, 0), ["v0.1.0"]), ("a lower major version", (0, 9), ["v1.0.0"])]:
        try:
            release(lambda: line, releases(tags), [], [], releases(tags))
            print(f"FAILED: {name}: no error", file=sys.stderr)
            failures += 1
        except ValueError:
            pass
    for name, properties in [("no property", "group = studio.cubiq\n"), ("a full version", "cubiqVersion = 0.0.1\n")]:
        try:
            version_line(properties)
            print(f"FAILED: {name}: no error", file=sys.stderr)
            failures += 1
        except ValueError:
            pass
    if version_line("# Comment\ncubiqVersion = 2.13\n") != (2, 13):
        print("FAILED: reading cubiqVersion", file=sys.stderr)
        failures += 1
    print("release-version: self-test " + ("failed" if failures else "passed"))
    return 1 if failures else 0


def main():
    parser = argparse.ArgumentParser(description="Determines the version of the release of a Cubiq commit.")
    parser.add_argument("properties", nargs="?", default="gradle.properties", help="the gradle.properties file")
    parser.add_argument("--commit", default="HEAD", help="the commit, HEAD by default")
    parser.add_argument("--self-test", action="store_true", help="test the version rules")
    arguments = parser.parse_args()
    if arguments.self_test:
        return self_test()
    try:
        result = release(
            lambda: version_line(Path(arguments.properties).read_text(encoding="utf-8")),
            releases(git_tags()),
            releases(git_tags("--points-at", arguments.commit)),
            releases(git_tags("--contains", arguments.commit)),
            releases(git_tags("--merged", arguments.commit)),
        )
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"release-version: {error}", file=sys.stderr)
        return 1
    for key, value in result.items():
        print(f"{key}={value}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
