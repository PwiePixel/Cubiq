#!/usr/bin/env bash
# Starts a Cubiq server with a test plugin that blocks the server thread for 15 seconds on a lock held by another
# thread and then keeps it busy for 12 seconds, and checks that the watchdog reports both with useful information,
# without spinning on the CPU while it waits. Paper's watchdog used to spin on a full core while the server did not
# respond.
#
# Usage: scripts/watchdog-smoke-test.sh <paperclip-jar> <server-directory>
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
stall_millis=15000
busy_millis=12000
# The watchdog thread may use this much CPU time while the server does not respond, mostly for the thread dump
max_watchdog_cpu_millis=1500

if ! compgen -G "$directory/libraries/*" > /dev/null; then
    echo "FAILED: $directory contains no extracted libraries; start the server there once first" >&2
    exit 1
fi

work=$(mktemp -d)
plugin="$directory/plugins/WatchdogSmokeTest.jar"
cleanup() {
    rm -rf "$work"
    rm -f "$plugin"
}
trap cleanup EXIT

classpath=$(find "$directory/libraries" -name '*.jar' -exec printf '%s:' {} +)
sources=()
while IFS= read -r source; do
    sources+=("$source")
done < <(find "$scripts/watchdog-test-plugin/src" -name '*.java')
"${bin}javac" --release 25 -nowarn -d "$work/classes" -cp "$classpath" "${sources[@]}"
cp "$scripts/watchdog-test-plugin/plugin.yml" "$work/classes/"
mkdir -p "$directory/plugins"
"${bin}jar" --create --file "$plugin" -C "$work/classes" .

# Paper's default watchdog settings: the first early warning after 10 seconds, a restart only after 60 seconds.
# The server is stopped once the plugin has finished both stalls.
SMOKE_EXPECT_NO_WATCHDOG_REPORTS=false SMOKE_WAIT_FOR="Watchdog smoke test finished" "$scripts/smoke-test.sh" \
    "$jar" "$directory" "-DcubiqSmokeTest.stallMillis=$stall_millis" "-DcubiqSmokeTest.busyMillis=$busy_millis"

log="$directory/smoke-test.log"
missing=0
expect() {
    if ! grep -qF -- "$1" "$log"; then
        echo "FAILED: the log does not contain: $1" >&2
        missing=1
    fi
}
expect "Blocking the server thread for $stall_millis ms"
expect "The server has not responded for "
expect "of CPU time in the last "
expect "so it is mostly waiting, for example for a lock, a file, the network or a database, or it gets too little CPU time from the operating system."
# The dump says which lock the server thread waits for, and dumps the thread that holds it
expect "State: BLOCKED"
expect "Blocked on: java.lang.Object@"
expect "held by \"WatchdogSmokeTest lock holder\""
expect "The server thread waits for a lock held by \"WatchdogSmokeTest lock holder\", which is likely the cause:"
expect "Current Thread: WatchdogSmokeTest lock holder"
expect "Locked monitors:"
expect "WatchdogSmokeTest.jar//studio.cubiq.smoketest.WatchdogSmokeTestPlugin.holdLockInWatchdogSmokeTest("
expect "The server thread is free again"
expect "While the server thread was blocked, the watchdog thread used "
# The busy server thread is reported as busy, with the plugin's code on its stack, which also shows that the CPU
# time of the right thread is measured
expect "Keeping the server thread busy for $busy_millis ms"
expect "so it is busy, for example in an endless loop or a very long computation."
expect "WatchdogSmokeTest.jar//studio.cubiq.smoketest.WatchdogSmokeTestPlugin.spinInWatchdogSmokeTest("

grep -E 'Blocking the server thread|Keeping the server thread busy|The server has not responded|of CPU time in the last|free again|no longer busy|watchdog thread used' "$log" || true
watchdog_cpu=$(grep -oE 'the watchdog thread used [0-9]+ ms' "$log" | grep -oE '[0-9]+' | head -n 1 || true)
if [[ -z $watchdog_cpu ]]; then
    missing=1
elif ((watchdog_cpu > max_watchdog_cpu_millis)); then
    echo "FAILED: the watchdog thread used ${watchdog_cpu} ms of CPU time while the server did not respond (allowed: ${max_watchdog_cpu_millis} ms)" >&2
    missing=1
fi
if grep -qF 'The server has stopped responding!' "$log"; then
    echo "FAILED: the watchdog treated the stall as a long timeout" >&2
    missing=1
fi
# One or two early warnings per stall, five seconds apart; more would mean that the watchdog repeats them too often
warnings=$(grep -c 'The server has not responded for ' "$log" || true)
if ((warnings < 2 || warnings > 4)); then
    echo "FAILED: expected 2 to 4 early warnings for the two stalls, found $warnings" >&2
    missing=1
fi
if [[ $missing -ne 0 ]]; then
    echo "----- watchdog output in the log -----" >&2
    grep -A 40 'The server has not responded' "$log" >&2 || true
    exit 1
fi
echo "The watchdog reported the blocked and the busy server thread ($warnings early warnings) and used ${watchdog_cpu} ms of CPU time while the server thread was blocked"
