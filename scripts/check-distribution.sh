#!/usr/bin/env bash
# Checks the Paperclip jar that server owners download, and optionally the server jar that Paperclip created from it:
# - the license texts and notices are included,
# - it contains no Minecraft or Mojang classes and no Mojang libraries as entries of their own: the server is only
#   included as a patch against Mojang's jar, which Paperclip downloads from Mojang on the first start (the patch
#   itself contains the classes that Paper, Purpur and Cubiq change or add; its content is not checked),
# - every entry has the same fixed time, so that builds of the same commit are identical.
#
# Usage: scripts/check-distribution.sh <paperclip-jar> [<server-directory>]
#
# With a server directory in which the jar was started before (for example by scripts/smoke-test.sh), the server jar
# that Paperclip created there (versions/<version>/cubiq-<version>.jar) is checked as well.
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Usage: $0 <paperclip-jar> [<server-directory>]" >&2
    exit 2
fi

jar=$1
directory=${2:-}
licenses=$(dirname "$(realpath "$0")")/../licenses
failures=0

fail() {
    echo "FAILED: $1" >&2
    failures=$((failures + 1))
}

entries=$(unzip -Z1 "$jar")

has_entry() {
    grep -qxF -- "$2" <<< "$1"
}

# Metadata that Paperclip needs
for entry in META-INF/MANIFEST.MF META-INF/main-class META-INF/download-context META-INF/versions.list \
    META-INF/patches.list META-INF/libraries.list; do
    has_entry "$entries" "$entry" || fail "$jar does not contain $entry"
done

# License texts and notices: Cubiq's, the upstream projects', and those of Paperclip and the libraries it includes
for license in Cubiq-LICENSE.txt Cubiq-NOTICE.txt GPL-3.0.txt LGPL-3.0.txt Paper-LICENSE.md Paper-MIT.md \
    Purpur-LICENSE.txt paperclip-LICENSE.txt jbsdiff-LICENSE.txt commons-compress-LICENSE.txt THIRD-PARTY-NOTICES.md; do
    has_entry "$entries" "META-INF/license/$license" || fail "$jar does not contain META-INF/license/$license"
done

# The license texts and notices of the bundled libraries whose jars contain none (licenses/third-party/), which must be
# the copies recorded in scripts/library-notices.json, byte for byte, and the overview of them
third_party=()
while IFS= read -r file; do
    third_party+=("${file#"$licenses/third-party/"}")
done < <(find "$licenses/third-party" -type f | sort)
[[ ${#third_party[@]} -gt 0 ]] || fail "$licenses/third-party contains no files"
if ! recorded=$(python3 -c 'import json, sys
for name, entry in json.load(open(sys.argv[1]))["files"].items():
    print(entry["sha256"], name)' "$licenses/../scripts/library-notices.json"); then
    fail "cannot read the recorded copies from scripts/library-notices.json"
    recorded=""
elif [[ -z $recorded ]]; then
    fail "scripts/library-notices.json records no files"
fi

check_third_party() {
    local jar=$1 entries=$2 file entry sha256
    for file in "${third_party[@]}"; do
        entry="META-INF/license/third-party/$file"
        has_entry "$entries" "$entry" || fail "$jar does not contain $entry"
    done
    while read -r sha256 file; do
        [[ -n $file ]] || continue
        entry="META-INF/license/third-party/$file"
        if ! has_entry "$entries" "$entry"; then
            # Reported above if the file is in licenses/third-party/
            [[ -f $licenses/third-party/$file ]] || fail "$jar does not contain $entry"
        elif [[ $(unzip -p "$jar" "$entry" | sha256sum | cut -d ' ' -f 1) != "$sha256" ]]; then
            fail "$entry in $jar differs from the copy recorded in scripts/library-notices.json"
        fi
    done <<< "$recorded"
    if has_entry "$entries" META-INF/license/THIRD-PARTY-NOTICES.md \
        && ! unzip -p "$jar" META-INF/license/THIRD-PARTY-NOTICES.md | cmp -s - "$licenses/THIRD-PARTY-NOTICES.md"; then
        fail "META-INF/license/THIRD-PARTY-NOTICES.md in $jar differs from licenses/THIRD-PARTY-NOTICES.md"
    fi
}

check_third_party "$jar" "$entries"

# No Minecraft or Mojang classes and no Mojang libraries as entries, and the server only as a patch
if minecraft=$(grep -E '^(net/minecraft|com/mojang)/' <<< "$entries"); then
    fail "$jar contains Minecraft classes: $(head -n 3 <<< "$minecraft" | tr '\n' ' ')"
fi
if mojang_libraries=$(grep -E '^META-INF/libraries/com/mojang/' <<< "$entries"); then
    fail "$jar contains Mojang libraries: $(head -n 3 <<< "$mojang_libraries" | tr '\n' ' ')"
fi
if server_jars=$(grep -E '^META-INF/versions/.+\.jar$' <<< "$entries"); then
    fail "$jar contains a server jar instead of a patch: $server_jars"
fi
patches=$(grep -cE '^META-INF/versions/.+\.jar\.patch$' <<< "$entries" || true)
[[ $patches -eq 1 ]] || fail "$jar contains $patches server patches instead of one"
if ! unzip -p "$jar" META-INF/download-context | grep -qE $'\thttps://piston-data\\.mojang\\.com/'; then
    fail "the download context of $jar does not point to piston-data.mojang.com"
fi

# Reproducibility: every entry has the same fixed time
if unnormalized=$(unzip -Z -T "$jar" | awk 'NR > 2 && $7 ~ /^[0-9]{8}\.[0-9]{6}$/ && $7 != "19800201.000000" { print $8 }' | grep .); then
    fail "$jar contains entries with a build time instead of the fixed time: $(head -n 3 <<< "$unnormalized" | tr '\n' ' ')"
fi

if [[ -n $directory ]]; then
    server_jars=()
    while IFS= read -r server_jar; do
        server_jars+=("$server_jar")
    done < <(find "$directory/versions" -name 'cubiq-*.jar' 2>/dev/null)
    if [[ ${#server_jars[@]} -ne 1 ]]; then
        fail "expected one cubiq-*.jar in $directory/versions, found ${#server_jars[@]}"
    else
        server_jar=${server_jars[0]}
        server_entries=$(unzip -Z1 "$server_jar")
        for license in Cubiq-LICENSE.txt Cubiq-NOTICE.txt GPL-3.0.txt LGPL-3.0.txt Paper-LICENSE.md Paper-MIT.md Purpur-LICENSE.txt \
            THIRD-PARTY-NOTICES.md; do
            has_entry "$server_entries" "META-INF/license/$license" || fail "$server_jar does not contain META-INF/license/$license"
        done
        check_third_party "$server_jar" "$server_entries"
        # Mojang's notice that the Minecraft code is subject to the EULA stays in place
        has_entry "$server_entries" "META-INF/LICENSE" || fail "$server_jar does not contain Mojang's META-INF/LICENSE"
        unzip -p "$server_jar" META-INF/MANIFEST.MF | grep -q '^Brand-Id: cubiqstudio:cubiq' || fail "the manifest of $server_jar does not identify Cubiq"
    fi
fi

if [[ $failures -ne 0 ]]; then
    exit 1
fi
echo "The distribution is complete: $(wc -l <<< "$entries") entries, license texts present, no Minecraft classes outside the server patch, reproducible entry times"
