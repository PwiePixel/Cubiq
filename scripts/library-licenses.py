#!/usr/bin/env python3
"""Lists the libraries in a Cubiq Paperclip jar with their licenses, and checks the licenses against a policy.

Usage: scripts/library-licenses.py <paperclip-jar> > docs/LIBRARIES.md

The licenses are taken from the Maven POMs of the libraries, following parent POMs, in the Gradle cache of the build
that created the jar ($GRADLE_USER_HOME, by default ~/.gradle). scripts/library-licenses.json maps the license names
that the POMs declare to license identifiers, says which licenses are allowed and which are open questions, and
records the libraries whose POMs declare no license.

The inventory is written to standard output. The script fails when a library has a license name, a license or no
license that the policy does not cover, so that every new license is reviewed before it is distributed.
"""

import json
import os
import sys
import xml.etree.ElementTree as ElementTree
import zipfile
from pathlib import Path

POLICY = Path(__file__).resolve().parent / "library-licenses.json"
MAVEN_NAMESPACE = "{http://maven.apache.org/POM/4.0.0}"
CUBIQ_GROUP = "studio.cubiq"


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


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    policy = json.loads(POLICY.read_text(encoding="utf-8"))
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    cache = gradle_home / "caches" / "modules-2" / "files-2.1"

    with zipfile.ZipFile(sys.argv[1]) as jar:
        bundled = {name[len("META-INF/libraries/"):] for name in jar.namelist() if name.startswith("META-INF/libraries/") and name.endswith(".jar")}
        listed = [line.split("\t") for line in jar.read("META-INF/libraries.list").decode("utf-8").splitlines() if line]

    rows, mojang, open_questions, notes, problems = [], [], [], [], []
    license_counts = {}
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
        override = policy["libraries"].get(f"{group}:{artifact}")
        if group == CUBIQ_GROUP:
            version = "this build"
        if override is not None:
            key, declared, source = override.get("license"), override.get("declared", "none"), override["source"]
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
                key = license_key(policy, name, url)
                if key is None:
                    problems.append(f'{module}: unknown license name "{name}" <{url}>; map it in {POLICY.name}')
                keys.append(key or "?")
            key, declared = " OR ".join(dict.fromkeys(keys)), "; ".join(f'"{name}"' for name, _ in licenses)
        status = policy["licenses"].get(key, {}).get("status") if key else None
        if override is not None and override.get("status"):
            status = override["status"]
        if status is None:
            if "?" not in (key or ""):
                # Unknown license names have been reported above
                problems.append(f"{module}: the license {key} is not in {POLICY.name}")
            status = "?"
        license_counts[key or "unknown"] = license_counts.get(key or "unknown", 0) + 1
        if status == "open":
            note = (override or {}).get("note") or policy["licenses"].get(key, {}).get("note", "")
            open_questions.append(f"- `{module}` {version}: {note}")
        elif override is not None and override.get("note"):
            notes.append(f"- `{module}`: {override['note']}")
        rows.append(f"| `{module}` | {version} | {key or 'unknown'} | {declared} | {source} | {status} |")

    print("# Bundled libraries")
    print()
    print("Generated by `scripts/library-licenses.py` from the Paperclip jar; do not edit this file by hand. After a dependency")
    print("or upstream update, regenerate it with `scripts/library-licenses.py cubiq-server/build/libs/cubiq-paperclip-*.jar >")
    print("docs/LIBRARIES.md`. CI fails when this file is out of date or when a library has a license that")
    print("`scripts/library-licenses.json` does not cover. See [LICENSING.md](LICENSING.md) for how the licenses fit together.")
    print()
    print("The license is taken from the library's Maven POM or from its nearest parent POM that declares one. `allowed` means")
    print("that the license is compatible with distributing the server under the GPLv3; `open` marks a question the Cubiq")
    print("maintainers still have to answer before a release.")
    print()
    print("## Licenses")
    print()
    print("| License | Libraries | Status | Note |")
    print("| --- | --- | --- | --- |")
    for key, count in sorted(license_counts.items(), key=lambda entry: (-entry[1], entry[0])):
        entry = policy["licenses"].get(key, {})
        status = entry.get("status", "see below")
        print(f"| {key} | {count} | {status} | {entry.get('note', '')} |")
    print()
    print(f"## Libraries in the Paperclip jar ({len(rows)})")
    print()
    print("| Library | Version | License | Declared as | Source | Status |")
    print("| --- | --- | --- | --- | --- | --- |")
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


if __name__ == "__main__":
    sys.exit(main())
