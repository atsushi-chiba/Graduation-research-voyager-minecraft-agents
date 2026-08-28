#!/usr/bin/env bash
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
bridge_dir="${DEMO_BRIDGE_DIR:-$script_dir/voyager/env/minecolonies-bridge}"
server_dir="${DEMO_SERVER_DIR:-/root/mc-server-forge}"
mods_dir="${DEMO_MODS_DIR:-$server_dir/mods}"
bridge_url="${DEMO_BRIDGE_URL:-http://localhost:8089}"
failed=0

ok() { printf 'OK   %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1" >&2; failed=1; }
info() { printf 'INFO %s\n' "$1"; }

for command_name in bash curl git; do
    if command -v "$command_name" >/dev/null 2>&1; then
        ok "$command_name is available"
    else
        bad "$command_name is not available"
    fi
done

java_command=java
if [ -n "${DEMO_JAVA_HOME:-}" ]; then
    java_command="$DEMO_JAVA_HOME/bin/java"
elif [ -n "${JAVA_HOME:-}" ]; then
    java_command="$JAVA_HOME/bin/java"
fi

if [ -x "$java_command" ] || command -v "$java_command" >/dev/null 2>&1; then
    java_version="$($java_command -version 2>&1 | head -n 1)"
    case "$java_version" in
        *' 17.'*|*'"17.'*) ok "JDK 17: $java_version" ;;
        *) bad "JDK 17 is required; found: $java_version" ;;
    esac
else
    bad "Java not found; set DEMO_JAVA_HOME to a JDK 17 directory"
fi

for required_path in \
    "$bridge_dir/gradlew" \
    "$server_dir/run.sh" \
    "$server_dir/start_server.sh" \
    "$server_dir/stop_server.sh" \
    "$mods_dir/minecolonies-1.20.1-1.1.1231.jar" \
    "$mods_dir/structurize-1.20.1-1.0.816.jar" \
    "$mods_dir/domum_ornamentum-1.20.1-1.0.296-universal.jar"
do
    if [ -e "$required_path" ]; then
        ok "$required_path"
    else
        bad "missing: $required_path"
    fi
done

server_pids="$(pgrep -f 'libraries/net/minecraftforge/forge/.*/unix_args.txt' 2>/dev/null || true)"
if [ -n "$server_pids" ]; then
    info "Forge is already running: $server_pids"
    if curl --fail --silent --max-time 3 "$bridge_url/ping" | grep -q '"status":"ok"'; then
        ok "running Bridge answered at $bridge_url"
    else
        bad "Forge is running but Bridge did not answer at $bridge_url"
    fi
else
    ok "Forge is stopped; deployment is safe"
fi

demo_daemons="$(pgrep -fa 'node .*persona_daemon\.js|node .*social_.*\.js|node .*supply_bot\.js|node .*council\.js' 2>/dev/null || true)"
if [ -n "$demo_daemons" ]; then
    bad "demo-excluded Node daemon is running:\n$demo_daemons"
else
    ok "persona/social/supply/council daemons are not running"
fi

info "repository: $script_dir"
info "bridge source: $bridge_dir"
info "Forge server: $server_dir"
info "mods: $mods_dir"
info "Bridge URL: $bridge_url"

if [ "$failed" -ne 0 ]; then
    exit 1
fi
