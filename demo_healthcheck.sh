#!/usr/bin/env bash
set -eu

bridge_url="${DEMO_BRIDGE_URL:-http://localhost:8089}"
game_host="${DEMO_GAME_HOST:-localhost}"
game_port="${DEMO_GAME_PORT:-25566}"

ping_payload="$(curl --fail --silent --show-error --max-time 5 "$bridge_url/ping")"
case "$ping_payload" in
    *'"status":"ok"'*) ;;
    *) printf 'FAIL unexpected /ping response: %s\n' "$ping_payload" >&2; exit 1 ;;
esac

status_file="$(mktemp)"
trap 'rm -f "$status_file"' EXIT
curl --fail --silent --show-error --max-time 10 "$bridge_url/status" > "$status_file"

if command -v node >/dev/null 2>&1; then
    summary="$(node -e '
const fs = require("fs");
const colonies = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
if (!Array.isArray(colonies) || colonies.length === 0) process.exit(2);
const colony = colonies[0];
process.stdout.write(`colony=${colony.name} id=${colony.id} citizens=${colony.citizens.length} buildings=${colony.buildings.length}`);
' "$status_file")"
else
    grep -q '"citizens"' "$status_file" || { printf 'FAIL /status has no citizens field\n' >&2; exit 1; }
    summary='colony status JSON received (install Node.js for counts)'
fi

server_pids="$(pgrep -f 'libraries/net/minecraftforge/forge/.*/unix_args.txt' 2>/dev/null || true)"
server_count="$(printf '%s\n' "$server_pids" | awk 'NF { count++ } END { print count+0 }')"
if [ "$server_count" -ne 1 ]; then
    printf 'FAIL expected exactly one Forge process; found %s: %s\n' "$server_count" "$server_pids" >&2
    exit 1
fi

demo_daemons="$(pgrep -fa 'node .*persona_daemon\.js|node .*social_.*\.js|node .*supply_bot\.js|node .*council\.js' 2>/dev/null || true)"
if [ -n "$demo_daemons" ]; then
    printf 'FAIL demo-excluded Node daemon is running:\n%s\n' "$demo_daemons" >&2
    exit 1
fi

printf 'OK   Bridge %s\n' "$ping_payload"
printf 'OK   %s\n' "$summary"
printf 'OK   exactly one Forge process: %s\n' "$server_pids"
printf 'OK   persona/social/supply/council daemons are not running\n'
printf 'READY Minecraft client: %s:%s\n' "$game_host" "$game_port"
