#!/usr/bin/env python3
"""Lists the libraries in a Cubiq Paperclip jar with their licenses and license notices, and checks them against a policy.

Usage: scripts/library-licenses.py <paperclip-jar> > docs/LIBRARIES.md
       scripts/library-licenses.py --notices > licenses/THIRD-PARTY-NOTICES.md

The licenses are taken from scripts/library-notices.json for the libraries that have an entry there, and otherwise
from the Maven POMs of the libraries, following parent POMs, in the Gradle cache of the build that created the jar
($GRADLE_USER_HOME, by default ~/.gradle). scripts/library-licenses.json maps the license names that the POMs declare to
license identifiers, says which licenses are allowed and which are open questions, and records the libraries whose
POMs declare no license.

scripts/library-notices.json has an entry for every library whose jar contains no license or notice file, and for
libraries whose own files lack notices that apply to them or do not say where their source code is. It records the
license texts and notices that Cubiq ships for them in licenses/third-party/, the version they were collected for, and
the source of each file; under "embedded", it records the same for code of other projects that is compiled into the
server jar or the Paperclip jar itself. With --notices, the script writes the overview of these files that is shipped
next to them.

The inventory is written to standard output. The script fails when a library has a license name, a license or no
license that the policy does not cover, when a library ships no license notices and has no current entry in
scripts/library-notices.json, or when a file in licenses/third-party/ is missing, changed or not used, so that every
new library and license is reviewed before it is distributed.
"""

import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ElementTree
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
POLICY = ROOT / "scripts" / "library-licenses.json"
NOTICES = ROOT / "scripts" / "library-notices.json"
THIRD_PARTY = ROOT / "licenses" / "third-party"
MAVEN_NAMESPACE = "{http://maven.apache.org/POM/4.0.0}"
CUBIQ_GROUP = "studio.cubiq"
STATUSES = ("allowed", "open")
# Files in a library jar that carry its license or notices
NOTICE_FILE = re.compile(r"(^|/)(META-INF/)?[^/]*(license|notice|copying|copyright)[^/]*$", re.IGNORECASE)


def child_text(element, name):
    child = element.find(MAVEN_NAMESPACE + name)
    if child is None:
        child = element.find(name)
    return child.text.strip() if child is not None and child.text else None


def find_pom(cache, group, artifact, version):
    directory = cache / group / artifact / version
    if not directory.is_dir():
        return None
    poms = sorted(directory.glob(f"*/{artifact}-{version}.pom"))
    return poms[0] if poms else None


def declared_licenses(cache, group, artifact, version):
    """Returns the licenses that the POM of a module or of its nearest parent declares, and where they come from."""
    coordinates = f"{group}:{artifact}:{version}"
    for _ in range(20):
        pom = find_pom(cache, group, artifact, version)
        if pom is None:
            raise LookupError(f"the POM of {group}:{artifact}:{version} is not in the Gradle cache")
        root = ElementTree.parse(pom).getroot()
        licenses_element = root.find(MAVEN_NAMESPACE + "licenses")
        if licenses_element is None:
            licenses_element = root.find("licenses")
        if licenses_element is not None:
            licenses = [
                (child_text(element, "name") or "", child_text(element, "url") or "")
                for element in licenses_element
                if element.tag in (MAVEN_NAMESPACE + "license", "license")
            ]
            if licenses:
                source = "POM" if f"{group}:{artifact}:{version}" == coordinates else f"parent POM {group}:{artifact}:{version}"
                return licenses, source
        parent = root.find(MAVEN_NAMESPACE + "parent")
        if parent is None:
            parent = root.find("parent")
        if parent is None:
            return [], "POM"
        group, artifact, version = child_text(parent, "groupId"), child_text(parent, "artifactId"), child_text(parent, "version")
    raise LookupError(f"the parent POMs of {coordinates} are nested too deeply")


def license_key(policy, name, url):
    names = policy["licenseNames"]
    for candidate in (f"{name} <{url}>", name):
        if candidate in names:
            return names[candidate]
    return None


def components(expression):
    """Splits a license expression such as "MIT AND GPL-3.0-or-later WITH GCC-exception-3.1" into its licenses."""
    return [part.strip() for part in re.split(r"\s+(?:AND|OR)\s+", expression) if part.strip()]


def status_of(policy, expression):
    """The status of a license expression: open if any of its licenses is open, ? if it names no license or one that
    the policy does not cover with the status allowed or open."""
    statuses = [policy["licenses"].get(part, {}).get("status") for part in components(expression)]
    if not statuses or any(status not in STATUSES for status in statuses):
        return "?"
    return "open" if "open" in statuses else "allowed"


def short_source(source):
    """Shortens "https://github.com/owner/repo.git <commit>" to "owner/repo <commit prefix>"."""
    match = re.match(r"https://(?:github\.com|gitlab\.ow2\.org|bitbucket\.org)/([^ ]+?)(?:\.git)? ([0-9a-f]{40})$", source)
    return f"{match.group(1)} {match.group(2)[:7]}" if match else source


def check_notice_files(notices, problems):
    """Checks that the files in licenses/third-party/ are exactly the recorded, unchanged ones."""
    recorded = notices["files"]
    for name, entry in recorded.items():
        path = THIRD_PARTY / name
        if not path.is_file():
            problems.append(f"licenses/third-party/{name} is missing")
        elif hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            problems.append(f"licenses/third-party/{name} differs from the copy recorded in {NOTICES.name}")
    present = {path.relative_to(THIRD_PARTY).as_posix() for path in THIRD_PARTY.rglob("*") if path.is_file()} if THIRD_PARTY.is_dir() else set()
    for name in sorted(present - set(recorded)):
        problems.append(f"licenses/third-party/{name} is not recorded in {NOTICES.name}")
    entries = {**notices["libraries"], **notices["embedded"]}
    used = {name for entry in entries.values() for name in entry["files"]}
    for name in sorted(set(recorded) - used):
        problems.append(f"licenses/third-party/{name} is recorded in {NOTICES.name} but no entry uses it")
    for name, entry in entries.items():
        if not entry["license"].strip():
            problems.append(f"{name}: {NOTICES.name} gives no license for it")
        for file in entry["files"]:
            if file not in recorded:
                problems.append(f"{name}: {NOTICES.name} lists licenses/third-party/{file}, which it does not record")
    for name, entry in notices["embedded"].items():
        if not entry["files"]:
            problems.append(f"{name}: {NOTICES.name} lists no license or notice files for it")


def print_inventory(jar_path):
    policy = json.loads(POLICY.read_text(encoding="utf-8"))
    notices = json.loads(NOTICES.read_text(encoding="utf-8"))
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    cache = gradle_home / "caches" / "modules-2" / "files-2.1"

    with zipfile.ZipFile(jar_path) as jar:
        bundled = {name[len("META-INF/libraries/"):]: name for name in jar.namelist() if name.startswith("META-INF/libraries/") and name.endswith(".jar")}
        listed = [line.split("\t") for line in jar.read("META-INF/libraries.list").decode("utf-8").splitlines() if line]
        notices_in_jar = {}
        for path, name in bundled.items():
            with zipfile.ZipFile(jar.open(name)) as library:
                notices_in_jar[path] = any(NOTICE_FILE.search(entry) and not entry.endswith((".class", "/")) for entry in library.namelist())

    problems = []
    check_notice_files(notices, problems)
    rows, mojang, open_questions, notes = [], [], [], []
    license_counts = {}
    covered = set()
    for _, coordinates, path in sorted(listed, key=lambda entry: entry[1]):
        group, artifact, version, *classifier = coordinates.split(":")
        module = f"{group}:{artifact}"
        if classifier:
            # For example the native libraries of Netty for each platform
            module += ":" + classifier[0]
        if path not in bundled:
            # Paperclip takes it from Mojang's server jar, which it downloads from Mojang
            mojang.append((module, version))
            continue
        override = policy["libraries"].get(module) or {}
        library = notices["libraries"].get(module)
        unknown_name = False
        if library is not None:
            covered.add(module)
            if library["version"] != version:
                problems.append(f"{module}: {NOTICES.name} has the notices of version {library['version']}, but {version} is bundled; "
                                "collect them again for this version")
            if library.get("noticesInJar", False) != notices_in_jar[path]:
                problems.append(f"{module}: {NOTICES.name} says that its jar contains {'a' if library.get('noticesInJar') else 'no'} license "
                                f"or notice file, but it contains {'one' if notices_in_jar[path] else 'none'}; review the entry")
        if group == CUBIQ_GROUP:
            version = "this build"

        if library is not None:
            key, source = library["license"], short_source(library["source"])
            try:
                licenses, _ = declared_licenses(cache, group, artifact, version)
            except LookupError:
                licenses = []
            declared = "; ".join(f'"{name}"' for name, _ in licenses) or "none"
        elif override.get("license"):
            key, declared, source = override["license"], override.get("declared", "none"), override["source"]
        else:
            try:
                licenses, source = declared_licenses(cache, group, artifact, version)
            except LookupError as ex:
                problems.append(f"{module}: {ex}")
                continue
            if not licenses:
                problems.append(f"{module}: its POM declares no license; record it in {POLICY.name}")
                continue
            keys = []
            for name, url in licenses:
                found = license_key(policy, name, url)
                if found is None:
                    problems.append(f'{module}: unknown license name "{name}" <{url}>; map it in {POLICY.name}')
                    unknown_name = True
                keys.append(found or "?")
            key, declared = " OR ".join(dict.fromkeys(keys)), "; ".join(f'"{name}"' for name, _ in licenses)

        status = override.get("status") or status_of(policy, key)
        for part in components(key):
            if part not in policy["licenses"]:
                # "?" stands for a license name that was reported above
                if part != "?" or not unknown_name:
                    problems.append(f"{module}: the license {part} is not in {POLICY.name}")
            elif policy["licenses"][part].get("status") not in STATUSES:
                problems.append(f"{module}: the license {part} has no status allowed or open in {POLICY.name}")
            license_counts[part] = license_counts.get(part, 0) + 1
        if status not in STATUSES and override.get("status"):
            problems.append(f"{module}: its status in {POLICY.name} is neither allowed nor open")

        places = ["in its jar"] if notices_in_jar[path] else []
        if library is not None:
            places += sorted({f"`{name.split('/')[0]}/`" for name in library["files"]})
        if not places and group == CUBIQ_GROUP:
            places = ["Cubiq's license files"]
        if not places:
            places = ["none"]
            if library is None:
                problems.append(f"{module}: its jar contains no license or notice file and {NOTICES.name} has no entry for it")
            else:
                problems.append(f"{module}: its jar contains no license or notice file and {NOTICES.name} lists none for it")
        shipped = ", ".join(places)

        if status == "open":
            open_parts = [part for part in components(key) if policy["licenses"].get(part, {}).get("status") == "open"]
            note = override.get("note") or " ".join(policy["licenses"][part].get("note", "") for part in open_parts)
            open_questions.append(f"- `{module}` {version}: {note}")
        elif override.get("note"):
            notes.append(f"- `{module}`: {override['note']}")
        rows.append(f"| `{module}` | {version} | {key} | {declared} | {source} | {shipped} | {status} |")

    for module in sorted(set(notices["libraries"]) - covered):
        problems.append(f"{module}: {NOTICES.name} has an entry for it, but it is not bundled")

    print("# Bundled libraries")
    print()
    print("Generated by `scripts/library-licenses.py` from the Paperclip jar; do not edit this file by hand. After a dependency")
    print("or upstream update, regenerate it with `scripts/library-licenses.py cubiq-server/build/libs/cubiq-paperclip-*.jar >")
    print("docs/LIBRARIES.md`. CI fails when this file is out of date, when a library has a license that")
    print("`scripts/library-licenses.json` does not cover, or when the license notices of a library are missing. See")
    print("[LICENSING.md](LICENSING.md) for how the licenses fit together.")
    print()
    print("The license is taken from `scripts/library-notices.json` for the libraries that have an entry there, where it was")
    print("determined from the library's source at the bundled version, including code of other projects that the jar")
    print("contains; otherwise from the library's Maven POM or from its nearest parent POM that declares one. `allowed` means")
    print("that the license is compatible with distributing the server under the GPLv3; `open` marks a question the Cubiq")
    print("maintainers still have to answer before a release. The notices column says where the license texts and notices of")
    print("a library are: `in its jar` means that the jar contains a file named like a license or notice (LICENSE, NOTICE,")
    print("COPYING or COPYRIGHT), which the script does not check for completeness; the named folders are in")
    print("`licenses/third-party/`, which the server jar and the Paperclip jar contain under `META-INF/license/` together")
    print("with [THIRD-PARTY-NOTICES.md](../licenses/THIRD-PARTY-NOTICES.md). Where the files in a jar were found to lack")
    print("notices, or to lack where the source code is, the library has an entry in `scripts/library-notices.json` as well.")
    print()
    print("## Licenses")
    print()
    print("| License | Libraries | Status | Note |")
    print("| --- | --- | --- | --- |")
    for key, count in sorted(license_counts.items(), key=lambda entry: (-entry[1], entry[0])):
        entry = policy["licenses"].get(key, {})
        print(f"| {key} | {count} | {entry.get('status', '?')} | {entry.get('note', '')} |")
    print()
    print(f"## Libraries in the Paperclip jar ({len(rows)})")
    print()
    print("| Library | Version | License | Declared as | Source | Notices | Status |")
    print("| --- | --- | --- | --- | --- | --- | --- |")
    for row in rows:
        print(row)
    print()
    print("## Open questions")
    print()
    for line in open_questions or ["None."]:
        print(line)
    print()
    if notes:
        print("## Notes")
        print()
        for line in notes:
            print(line)
        print()
    print(f"## Libraries from Mojang's server jar ({len(mojang)})")
    print()
    print("Paperclip takes these from the server jar that it downloads from Mojang on the first start; Cubiq does not")
    print("distribute them.")
    print()
    for module, version in mojang:
        print(f"- `{module}` {version}")

    for problem in problems:
        print(f"library-licenses: {problem}", file=sys.stderr)
    return 1 if problems else 0


def print_entry(notices, title, entry, place, in_jar):
    print()
    print(f"### {title}")
    print()
    if place:
        print(f"Contained in: {place}")
        print()
    print(f"License: {entry['license']}")
    print()
    print(f"Source: {entry['source']}")
    print()
    if entry["copyright"]:
        print("Copyright notices:")
        print()
        for line in entry["copyright"]:
            print(f"- {' '.join(line.split())}")
        print()
    if not entry["files"]:
        print("License texts and notices: in the library's jar.")
    else:
        print("License texts and notices, in addition to those in the library's jar:" if in_jar else "License texts and notices:")
        print()
        for name in entry["files"]:
            file = notices["files"].get(name)
            if file is None:
                # Reported by check_notice_files
                continue
            origin = file["url"] if "url" in file else f"{file['repository']} {file['commit']}, {file['path']}"
            print(f"- `third-party/{name}` (from {origin})")
    if entry["note"]:
        print()
        print(entry["note"])


def print_notices():
    notices = json.loads(NOTICES.read_text(encoding="utf-8"))
    problems = []
    check_notice_files(notices, problems)
    print("# Third-party notices")
    print()
    print("This file lists the license texts and notices that Cubiq ships for code of other projects whose own files do not")
    print("carry them all: the bundled libraries whose jars contain no license or notice file, libraries whose jars lack")
    print("notices that apply to them or do not say where their source code is, and code of other projects that is compiled")
    print("into the server jar or the Paperclip jar itself. The files are in the folders of `third-party/` next to this file,")
    print("as their projects publish them; each was taken from the source given below, usually a commit of the project's")
    print("repository. Libraries whose jars contain all the license texts and notices that apply to them are not listed")
    print("here. The source code of the libraries under licenses that require it to be available, such as the GPL, the LGPL")
    print("and the MPL, is available from the repositories and commits given for them.")
    print()
    print("This file is generated by `scripts/library-licenses.py --notices` from `scripts/library-notices.json`.")
    print()
    print("## Libraries")
    for module, library in notices["libraries"].items():
        print_entry(notices, f"{module} {library['version']}", library, None, library.get("noticesInJar", False))
    print()
    print("## Code compiled into the server jar and the Paperclip jar")
    for name, embedded in notices["embedded"].items():
        print_entry(notices, f"{name} {embedded['version']}".strip(), embedded, embedded["in"], False)
    for problem in problems:
        print(f"library-licenses: {problem}", file=sys.stderr)
    return 1 if problems else 0


def main():
    if sys.argv[1:] == ["--notices"]:
        return print_notices()
    if len(sys.argv) != 2 or sys.argv[1].startswith("-"):
        print("\n".join(__doc__.strip().splitlines()[2:4]), file=sys.stderr)
        return 2
    return print_inventory(sys.argv[1])


if __name__ == "__main__":
    sys.exit(main())
