#!/usr/bin/env bash
# Starts a Cubiq server with a test plugin whose bootstrap, load, enable and disable steps are slow, and checks that
# the lifecycle diagnostics report each slow step with a stack trace and summarize how long the plugin took.
#
# Usage: scripts/lifecycle-smoke-test.sh <paperclip-jar> <server-directory>
#
# The server directory must contain a server that was started before (for example by scripts/smoke-test.sh),
# because the test plugin is compiled against the libraries that Paperclip extracted into it. The plugin is removed
# again afterwards. Like scripts/smoke-test.sh, this accepts the Minecraft EULA (https://aka.ms/MinecraftEULA).
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <paperclip-jar> <server-directory>" >&2
    exit 2
fi

scripts=$(dirname "$(realpath "$0")")
jar=$(realpath "$1")
directory=$(realpath "$2")
bin=${JAVA_HOME:+$JAVA_HOME/bin/}
step_delay_millis=3000

if ! compgen -G "$directory/libraries/*" > /dev/null; then
    echo "FAILED: $directory contains no extracted libraries; start the server there once first" >&2
    exit 1
fi

work=$(mktemp -d)
plugin="$directory/plugins/LifecycleSmokeTest.jar"
cleanup() {
    rm -rf "$work"
    rm -f "$plugin"
}
trap cleanup EXIT

# Compile the test plugin against the API and libraries of this server
classpath=$(find "$directory/libraries" -name '*.jar' -printf '%p:')
mapfile -t sources < <(find "$scripts/smoke-test-plugin/src" -name '*.java')
"${bin}javac" --release 25 -nowarn -d "$work/classes" -cp "$classpath" "${sources[@]}"
cp "$scripts/smoke-test-plugin/paper-plugin.yml" "$work/classes/"
mkdir -p "$directory/plugins"
"${bin}jar" --create --file "$plugin" -C "$work/classes" .

# Report steps after one second without progress; each step of the plugin takes three seconds
SMOKE_EXPECT_NO_WATCHDOG_REPORTS=false "$scripts/smoke-test.sh" "$jar" "$directory" \
    -Dcubiq.lifecycleWatchdog.warnAfter=1 \
    "-DcubiqSmokeTest.stepDelayMillis=$step_delay_millis"

log="$directory/smoke-test.log"
missing=0
expect() {
    if ! grep -qF -- "$1" "$log"; then
        echo "FAILED: the log does not contain: $1" >&2
        missing=1
    fi
}
for step in Bootstrapping Loading Enabling Disabling; do
    expect "Lifecycle watchdog: $step plugin LifecycleSmokeTest v1.0 has not finished after"
    expect "Lifecycle watchdog: $step plugin LifecycleSmokeTest v1.0 finished after"
done
# The stack traces show the plugin's code, with the plugin's jar as class loader
expect "LifecycleSmokeTest.jar//studio.cubiq.smoketest.LifecycleSmokeTestBootstrap.bootstrap("
expect "LifecycleSmokeTest.jar//studio.cubiq.smoketest.LifecycleSmokeTestPlugin.onLoad("
expect "LifecycleSmokeTest.jar//studio.cubiq.smoketest.LifecycleSmokeTestPlugin.onEnable("
expect "LifecycleSmokeTest.jar//studio.cubiq.smoketest.LifecycleSmokeTestPlugin.onDisable("
expect "Plugins took "
expect "to start (bootstrap "
expect "Plugin: LifecycleSmokeTest "
expect "Stopped the server in "
expect "Plugins took 3."

grep -E 'Lifecycle watchdog: .*(has not finished|finished after)|Plugins took|Stopped the server in' "$log" || true
if [[ $missing -ne 0 ]]; then
    echo "----- lifecycle diagnostics in the log -----" >&2
    grep -A 12 'Lifecycle watchdog' "$log" >&2 || true
    exit 1
fi
echo "Lifecycle diagnostics reported every slow step"
