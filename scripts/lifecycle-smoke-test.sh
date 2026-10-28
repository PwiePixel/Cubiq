#!/usr/bin/env bash
# Starts a Cubiq server with a test plugin whose bootstrap, load, enable and disable steps are slow, and checks that
# the lifecycle diagnostics report each slow step with a stack trace, summarize how long the plugin took, and break
# startup and shutdown down into their steps in the log and in the JSON report.
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
report="$directory/lifecycle-report.json"
cleanup() {
    rm -rf "$work"
    rm -f "$plugin" "$report"
}
trap cleanup EXIT

# Compile the test plugin against the API and libraries of this server
classpath=$(find "$directory/libraries" -name '*.jar' -exec printf '%s:' {} +)
sources=()
while IFS= read -r source; do
    sources+=("$source")
done < <(find "$scripts/smoke-test-plugin/src" -name '*.java')
"${bin}javac" --release 25 -nowarn -d "$work/classes" -cp "$classpath" "${sources[@]}"
cp "$scripts/smoke-test-plugin/paper-plugin.yml" "$work/classes/"
mkdir -p "$directory/plugins"
"${bin}jar" --create --file "$plugin" -C "$work/classes" .

# Report steps after one second without progress; each step of the plugin takes three seconds
run_server() {
    SMOKE_EXPECT_NO_WATCHDOG_REPORTS=false SMOKE_STOP_COMMAND=$1 "$scripts/smoke-test.sh" "$jar" "$directory" \
        -Dcubiq.lifecycleWatchdog.warnAfter=1 \
        -Dcubiq.lifecycleReport=lifecycle-report.json \
        "-DcubiqSmokeTest.stepDelayMillis=$step_delay_millis"
}

log="$directory/smoke-test.log"
missing=0
expect() {
    if ! grep -qF -- "$1" "$log"; then
        echo "FAILED: the log does not contain: $1" >&2
        missing=1
    fi
}
check() {
    grep -E 'Lifecycle watchdog: .*(has not finished|finished after)|Startup took|Plugins took|Stopped the server in' "$log" || true
    if [[ $missing -ne 0 ]]; then
        echo "----- lifecycle diagnostics in the log -----" >&2
        grep -A 12 'Lifecycle watchdog' "$log" >&2 || true
        exit 1
    fi
}

rm -f "$report"
run_server stop
for step in Bootstrapping Loading Enabling Disabling; do
    expect "Lifecycle watchdog: $step plugin LifecycleSmokeTest v1.0 has not finished after"
    expect "Lifecycle watchdog: $step plugin LifecycleSmokeTest v1.0 finished after"
done
# The stack traces show the plugin's code, with the plugin's jar as class loader
expect "LifecycleSmokeTest.jar//studio.cubiq.smoketest.LifecycleSmokeTestBootstrap.bootstrap("
expect "LifecycleSmokeTest.jar//studio.cubiq.smoketest.LifecycleSmokeTestPlugin.onLoad("
expect "LifecycleSmokeTest.jar//studio.cubiq.smoketest.LifecycleSmokeTestPlugin.onEnable("
expect "LifecycleSmokeTest.jar//studio.cubiq.smoketest.LifecycleSmokeTestPlugin.onDisable("
# The plugin's steps sleep, so the reports do not call them stuck
expect "The thread that runs it is waiting, possibly for work on another thread"
expect "Plugins took "
expect "to start (bootstrap "
expect "Plugin: LifecycleSmokeTest "
expect "Stopped the server in "
expect "Plugins took 3."
# The breakdowns of startup and shutdown
expect "Startup took "
expect ": bootstrapping "
expect ", worlds "
expect "(world "
expect "Stopped the server in "
expect ": disabling plugins 3."
check

# The report has the same breakdowns, with the times of the plugin, which sleeps in each of its steps
if ! python3 -I - "$report" "$step_delay_millis" <<'EOF'; then
import json
import sys

report = json.load(open(sys.argv[1], encoding="utf-8"))
delay = int(sys.argv[2])
problems = []
if report.get("formatVersion") != 1:
    problems.append(f"formatVersion is {report.get('formatVersion')}")
expected = {
    "startup": (["bootstrapping", "data packs", "server setup", "loading plugins", "enabling plugins", "worlds"], ["bootstrap", "load", "enable"]),
    "shutdown": (["disabling plugins", "worlds"], ["disable"]),
}
for part, (steps, plugin_steps) in expected.items():
    timing = report.get(part)
    if timing is None:
        problems.append(f"no {part}")
        continue
    names = [step["name"] for step in timing["steps"]]
    for name in steps + ["other"]:
        if name not in names:
            problems.append(f"{part}: no step {name} in {names}")
    if names[-1:] != ["other"]:
        problems.append(f"{part}: other is not the last step: {names}")
    total = sum(step["millis"] for step in timing["steps"])
    if abs(total - timing["totalMillis"]) > 1:
        problems.append(f"{part}: the steps add up to {total} ms, not {timing['totalMillis']} ms")
    worlds = next((step for step in timing["steps"] if step["name"] == "worlds"), {})
    subjects = [detail["name"] for detail in worlds.get("details", [])]
    if sorted(subjects) != ["world", "world_nether", "world_the_end"]:
        problems.append(f"{part}: the worlds are {subjects}")
    plugin = next((plugin for plugin in timing["plugins"] if plugin["name"] == "LifecycleSmokeTest"), None)
    if plugin is None:
        problems.append(f"{part}: no times of the plugin LifecycleSmokeTest")
        continue
    for step in plugin_steps:
        if plugin.get(step + "Millis", 0) < delay:
            problems.append(f"{part}: the plugin's {step} took {plugin.get(step + 'Millis')} ms, not at least {delay} ms")
for problem in problems:
    print(f"FAILED: lifecycle report: {problem}", file=sys.stderr)
sys.exit(1 if problems else 0)
EOF
    echo "----- lifecycle report -----" >&2
    cat "$report" >&2 || true
    exit 1
fi

# /restart stops the server on a path of its own; without a restart script it only stops the server
run_server restart
expect "Lifecycle watchdog: Disabling plugin LifecycleSmokeTest v1.0 has not finished after"
expect "Lifecycle watchdog: Disabling plugin LifecycleSmokeTest v1.0 finished after"
expect "Stopped the server in "
expect "Plugins took 3."
expect "to stop. Plugin: LifecycleSmokeTest "
check
echo "Lifecycle diagnostics reported every slow step"
