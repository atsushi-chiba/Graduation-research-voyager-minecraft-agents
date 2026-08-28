#!/usr/bin/env bash
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
bridge_dir="${DEMO_BRIDGE_DIR:-$script_dir/voyager/env/minecolonies-bridge}"
server_dir="${DEMO_SERVER_DIR:-/root/mc-server-forge}"
mods_dir="${DEMO_MODS_DIR:-$server_dir/mods}"
bridge_url="${DEMO_BRIDGE_URL:-http://localhost:8089}"
java_home="${DEMO_JAVA_HOME:-${JAVA_HOME:-}}"
deploy_jar="${DEMO_DEPLOY_JAR:-$mods_dir/voyagerbridge-0.1.0.jar}"
backup_dir="${DEMO_BACKUP_DIR:-$server_dir/open-campus-demo-backups}"
backup_record="$server_dir/.open-campus-demo-bridge-backup"
start_log="${DEMO_START_LOG:-$server_dir/open-campus-demo-start.log}"
forceload_cx="${DEMO_FORCELOAD_CX:-501}"
forceload_cz="${DEMO_FORCELOAD_CZ:--319}"
forceload_r="${DEMO_FORCELOAD_R:-80}"

if [ -z "$java_home" ]; then
    printf 'ERROR set DEMO_JAVA_HOME to the JDK 17 directory\n' >&2
    exit 1
fi

DEMO_BRIDGE_DIR="$bridge_dir" DEMO_SERVER_DIR="$server_dir" \
DEMO_MODS_DIR="$mods_dir" DEMO_BRIDGE_URL="$bridge_url" \
DEMO_JAVA_HOME="$java_home" "$script_dir/demo_preflight.sh"

if pgrep -f 'libraries/net/minecraftforge/forge/.*/unix_args.txt' >/dev/null 2>&1; then
    printf 'ERROR Forge is already running; use demo_healthcheck.sh or stop it before deployment\n' >&2
    exit 1
fi

printf '[1/4] Building VoyagerBridge with JDK 17...\n'
(
    cd "$bridge_dir"
    DEMO_MODS_DIR="$mods_dir" JAVA_HOME="$java_home" ./gradlew build -x test
)

built_jar="$bridge_dir/build/libs/voyagerbridge-0.1.0.jar"
if [ ! -f "$built_jar" ]; then
    printf 'ERROR build output not found: %s\n' "$built_jar" >&2
    exit 1
fi

printf '[2/4] Backing up and deploying the Bridge JAR...\n'
mkdir -p "$backup_dir"
recorded_backup=''
if [ -f "$backup_record" ]; then
    recorded_backup="$(sed -n '1p' "$backup_record")"
fi
case "$recorded_backup" in
    "$backup_dir"/*)
        if [ -f "$recorded_backup" ]; then
            printf 'Keeping original backup: %s\n' "$recorded_backup"
        else
            recorded_backup=''
        fi
        ;;
    *) recorded_backup='' ;;
esac

if [ -n "$recorded_backup" ]; then
    :
elif [ -f "$deploy_jar" ]; then
    timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
    backup_jar="$backup_dir/voyagerbridge-0.1.0.before-open-campus-$timestamp.jar"
    cp -p "$deploy_jar" "$backup_jar"
    printf '%s\n' "$backup_jar" > "$backup_record"
    printf 'Backup: %s\n' "$backup_jar"
else
    printf '%s\n' 'ABSENT' > "$backup_record"
    printf 'No previous Bridge JAR was present.\n'
fi
cp "$built_jar" "$deploy_jar"
sha256sum "$built_jar" "$deploy_jar"

printf '[3/4] Starting Forge in a detached session with all agents disabled...\n'
setsid -f env \
    FORCELOAD_CX="$forceload_cx" FORCELOAD_CZ="$forceload_cz" FORCELOAD_R="$forceload_r" \
    bash "$server_dir/start_server.sh" --no-agents >"$start_log" 2>&1 </dev/null

ready=0
for _attempt in $(seq 1 40); do
    if curl --fail --silent --max-time 3 "$bridge_url/ping" | grep -q '"status":"ok"'; then
        ready=1
        break
    fi
    sleep 3
done
if [ "$ready" -ne 1 ]; then
    printf 'ERROR Bridge did not become ready; inspect %s\n' "$start_log" >&2
    exit 1
fi

if [ -n "${DEMO_FORCELOAD_MIN_X:-}" ] && [ -n "${DEMO_FORCELOAD_MIN_Z:-}" ] && \
   [ -n "${DEMO_FORCELOAD_MAX_X:-}" ] && [ -n "${DEMO_FORCELOAD_MAX_Z:-}" ]; then
    printf 'forceload remove all\n' > "$server_dir/cmd_pipe"
    sleep 1
    printf 'forceload add %s %s %s %s\n' \
        "$DEMO_FORCELOAD_MIN_X" "$DEMO_FORCELOAD_MIN_Z" \
        "$DEMO_FORCELOAD_MAX_X" "$DEMO_FORCELOAD_MAX_Z" > "$server_dir/cmd_pipe"
fi

curl --fail --silent --show-error -X POST "$bridge_url/tickrate?auto=true" >/dev/null

printf '[4/4] Health check...\n'
DEMO_BRIDGE_URL="$bridge_url" "$script_dir/demo_healthcheck.sh"
printf 'Start log: %s\n' "$start_log"
