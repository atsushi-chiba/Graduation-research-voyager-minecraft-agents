#!/usr/bin/env bash
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
server_dir="${DEMO_SERVER_DIR:-/root/mc-server-forge}"
mods_dir="${DEMO_MODS_DIR:-$server_dir/mods}"
deploy_jar="${DEMO_DEPLOY_JAR:-$mods_dir/voyagerbridge-0.1.0.jar}"
backup_dir="${DEMO_BACKUP_DIR:-$server_dir/open-campus-demo-backups}"
backup_record="$server_dir/.open-campus-demo-bridge-backup"

bash "$server_dir/stop_server.sh"

if pgrep -f 'libraries/net/minecraftforge/forge/.*/unix_args.txt' >/dev/null 2>&1; then
    printf 'ERROR Forge is still running\n' >&2
    exit 1
fi
printf 'OK   Forge stopped\n'

if [ "${1:-}" = '--restore' ]; then
    if [ ! -f "$backup_record" ]; then
        printf 'ERROR backup record not found: %s\n' "$backup_record" >&2
        exit 1
    fi
    backup_jar="$(sed -n '1p' "$backup_record")"
    case "$backup_jar" in
        RESTORED:*) printf 'OK   original Bridge JAR was already restored\n'; exit 0 ;;
    esac
    if [ "$backup_jar" = 'ABSENT' ]; then
        printf 'NOTICE there was no original Bridge JAR; remove %s manually if required\n' "$deploy_jar"
        exit 0
    fi
    case "$backup_jar" in
        "$backup_dir"/*) ;;
        *) printf 'ERROR refusing backup path outside %s: %s\n' "$backup_dir" "$backup_jar" >&2; exit 1 ;;
    esac
    if [ ! -f "$backup_jar" ]; then
        printf 'ERROR backup JAR not found: %s\n' "$backup_jar" >&2
        exit 1
    fi
    cp -p "$backup_jar" "$deploy_jar"
    printf 'RESTORED:%s\n' "$backup_jar" > "$backup_record"
    printf 'RESTORED %s -> %s\n' "$backup_jar" "$deploy_jar"
    sha256sum "$backup_jar" "$deploy_jar"
fi

printf 'Repository was not modified by this stop operation: %s\n' "$script_dir"
