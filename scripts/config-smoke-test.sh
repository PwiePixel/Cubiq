#!/usr/bin/env bash
# Puts a mistake into each kind of configuration file that the configuration check covers, starts a Cubiq server with
# them and checks that the configuration check reports each mistake with the expected message. A second start
# simulates a server whose Paper configuration is from an older version, for which Paper rewrites server.properties.
#
# Usage: scripts/config-smoke-test.sh <paperclip-jar> <server-directory>
#
# The server directory must contain a server that was started before (for example by scripts/smoke-test.sh), so that
# all configuration files exist. The test runs on a copy, <server-directory>-config-check, and leaves the server
# directory unchanged. Like scripts/smoke-test.sh, this accepts the Minecraft EULA (https://aka.ms/MinecraftEULA).
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <paperclip-jar> <server-directory>" >&2
    exit 2
fi

scripts=$(dirname "$(realpath "$0")")
jar=$(realpath "$1")
source=$(realpath "$2")
directory="$source-config-check"

if [[ ! -f $source/server.properties || ! -f $source/config/paper-world-defaults.yml ]]; then
    echo "FAILED: $source contains no configuration files; start the server there once first" >&2
    exit 1
fi
rm -rf "$directory"
cp -a "$source" "$directory"
cd "$directory"

# Replaces the only line of a file that matches a pattern
replace_line() {
    local file=$1 pattern=$2 replacement=$3 matches
    matches=$(grep -c -- "$pattern" "$file" || true)
    if [[ $matches -ne 1 ]]; then
        echo "FAILED: expected one line matching '$pattern' in $file, found $matches" >&2
        exit 1
    fi
    awk -v pattern="$pattern" -v replacement="$replacement" '$0 ~ pattern { print replacement; next } { print }' \
        "$file" > "$file.new"
    mv "$file.new" "$file"
}

# Minecraft reads "true " with a space at the end as false, and uses the default for a value it cannot read
replace_line server.properties '^white-list=' 'white-list=true '
replace_line server.properties '^view-distance=' 'view-distance=twelve'
printf 'max-player=50\npvp=false\n' >> server.properties
replace_line bukkit.yml '^  monsters: ' '  monster: 70'
replace_line spigot.yml '^    mob-spawn-range: ' '    mob-spawn-range: eight'
replace_line purpur.yml '^  use-alternate-keepalive: ' '  use-alternate-keepaliv: false'
replace_line config/paper-global.yml '^  player-max-chunk-load-rate: ' '  player-max-chunk-load-rat: 100.0'
replace_line config/paper-world-defaults.yml '^  auto-save-interval: ' '  auto-save-intervall: default'
printf 'entities:\n  spawning:\n    spawn-limits:\n      monsters: 10\n' >> world/dimensions/minecraft/overworld/paper-world.yml

SMOKE_EXPECT_NO_WATCHDOG_REPORTS=true "$scripts/smoke-test.sh" "$jar" "$directory"

log="$directory/smoke-test.log"
missing=0
expect() {
    if ! grep -qF -- "$1" "$log"; then
        echo "FAILED: the log does not contain: $1" >&2
        missing=1
    fi
}
expect "Configuration check: found 10 problems in server.properties, bukkit.yml, purpur.yml, spigot.yml, config/paper-global.yml, config/paper-world-defaults.yml, world/dimensions/minecraft/overworld/paper-world.yml, "
expect "  server.properties: Unknown setting 'max-player' has no effect. Did you mean 'max-players'?"
expect "  server.properties: Setting 'pvp' has no effect anymore. Use the game rule pvp instead."
expect "  server.properties: 'view-distance' must be a whole number, but is \"twelve\", so the server uses the default value 10."
expect "  server.properties: 'white-list' must be true or false, but is \"true \" (with whitespace at the end), so the server uses false."
expect "  bukkit.yml: Unknown setting 'spawn-limits.monster' has no effect. Did you mean 'spawn-limits.monsters'?"
expect "  purpur.yml: Unknown setting 'settings.use-alternate-keepaliv' has no effect. Did you mean 'settings.use-alternate-keepalive'?"
expect "  spigot.yml: 'world-settings.default.mob-spawn-range' must be a number, but is \"eight\", so it is ignored."
expect "  config/paper-global.yml: Unknown setting 'chunk-loading-basic.player-max-chunk-load-rat' has no effect. Did you mean 'chunk-loading-basic.player-max-chunk-load-rate'?"
expect "  config/paper-world-defaults.yml: Unknown setting 'chunks.auto-save-intervall' has no effect. Did you mean 'chunks.auto-save-interval'?"
expect "  world/dimensions/minecraft/overworld/paper-world.yml: Unknown setting 'entities.spawning.spawn-limits.monsters' has no effect. Did you mean 'entities.spawning.spawn-limits.monster'?"

# Paper did not rewrite server.properties on this start, so no setting may carry the note that it was removed
if grep -qF "The server removed it from the file while starting." "$log"; then
    echo "FAILED: a setting was reported as removed although nothing removed it" >&2
    missing=1
fi

# Minecraft writes the values it uses to server.properties, as the messages say
for line in 'white-list=false' 'view-distance=10'; do
    if ! grep -qx -- "$line" server.properties; then
        echo "FAILED: server.properties does not contain: $line" >&2
        missing=1
    fi
done

if [[ $missing -ne 0 ]]; then
    exit 1
fi

# Start again like a server whose Paper configuration is from before Paper moved allow-nether out of
# server.properties: Paper moves its value to paper-global.yml and writes server.properties again with only the
# settings Minecraft writes back. The settings without effect are lost, so they must still be reported, but not
# allow-nether.
replace_line config/paper-global.yml '^_version: ' '_version: 30'
printf 'allow-nether=true\n' >> server.properties
SMOKE_EXPECT_NO_WATCHDOG_REPORTS=true "$scripts/smoke-test.sh" "$jar" "$directory"

expect "Configuration check: found 8 problems in server.properties, bukkit.yml, purpur.yml, spigot.yml, config/paper-global.yml, config/paper-world-defaults.yml, world/dimensions/minecraft/overworld/paper-world.yml, "
expect "  server.properties: Unknown setting 'max-player' has no effect. Did you mean 'max-players'? The server removed it from the file while starting."
expect "  server.properties: Setting 'pvp' has no effect anymore. Use the game rule pvp instead. The server removed it from the file while starting."
if grep -qF "server.properties: Setting 'allow-nether'" "$log"; then
    echo "FAILED: allow-nether was reported although Paper moved its value" >&2
    missing=1
fi
for key in allow-nether pvp max-player; do
    if grep -q "^$key=" server.properties; then
        echo "FAILED: Paper did not remove $key from server.properties, so this run did not test the update" >&2
        missing=1
    fi
done

if [[ $missing -ne 0 ]]; then
    exit 1
fi
echo "The configuration check reported every mistake"
