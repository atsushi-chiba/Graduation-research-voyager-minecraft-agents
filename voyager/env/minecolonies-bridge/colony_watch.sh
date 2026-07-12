#!/bin/bash
# colony_watch.sh - unattended colony monitor. Emits one line per actionable
# event; self-heals the node agents (they are safe to restart), only reports
# server/bridge problems (those need investigation, not blind restarts).
BRIDGE=http://localhost:8089
DIR=/root/Voyager/voyager/env/minecolonies-bridge
POLL=120
HEARTBEAT_EVERY=30   # polls -> 30*120s = 60 min
IDLE_THRESHOLD=3     # emit an event only when >= this many staffed-but-idle workplaces
bridge_down=0
n=0

while true; do
  n=$((n+1))

  # --- bridge / server alive ---
  if curl -s --max-time 5 $BRIDGE/ping 2>/dev/null | grep -q ok; then
    if [ "$bridge_down" = 1 ]; then echo "RECOVERED: bridge is answering again"; fi
    bridge_down=0
  else
    if [ "$bridge_down" = 0 ]; then echo "PROBLEM: bridge/server not answering (crash? boot?)"; fi
    bridge_down=1
    sleep $POLL; continue
  fi

  # --- agents: exactly one of each; restart if dead ---
  # Anchor with $ so the lingering `bash -c ...` setsid wrapper (whose cmdline
  # merely CONTAINS the string) doesn't count as a second instance.
  sb=$(pgrep -fc 'node supply_bot\.js$')
  if [ "$sb" = "0" ]; then
    (cd $DIR && setsid nohup node supply_bot.js >> supply_bot.log 2>&1 &)
    echo "ACTION: supply_bot was dead - restarted"
  elif [ "$sb" -gt 1 ]; then
    echo "PROBLEM: $sb supply_bot processes running (must be 1)"
  fi
  cl=$(pgrep -fc 'node council\.js$')
  if [ "$cl" = "0" ]; then
    (cd $DIR && setsid nohup node council.js >> council5.log 2>&1 &)
    echo "ACTION: council was dead (MAX_CYCLES reached?) - restarted"
  fi

  # --- colony health thresholds ---
  read -r sick starving citizens <<< "$(curl -s --max-time 10 $BRIDGE/status 2>/dev/null | python3 -c "
import json,sys
try:
    c=json.load(sys.stdin)[0]
    cit=c['citizens']
    print(sum(1 for x in cit if x.get('sick')),
          sum(1 for x in cit if x.get('saturation',99)<=2.5), len(cit))
except Exception:
    print('- - -')
" 2>/dev/null)"
  if [ "$sick" != "-" ] && [ -n "$sick" ]; then
    if [ "$sick" -gt 5 ] 2>/dev/null; then
      echo "PROBLEM: $sick citizens sick (sickness spiral watch: threshold 5)"
    fi
    if [ "$starving" -gt 10 ] 2>/dev/null; then
      echo "PROBLEM: $starving citizens starving (sat<=2.5)"
    fi
  fi

  # --- keepalive failure (colony brain freeze) ---
  if tail -200 /root/mc-server-forge/console.log 2>/dev/null | grep -q 'keepColoniesActive failed'; then
    echo "PROBLEM: keepColoniesActive failing (unattended colony will freeze)"
  fi

  # --- idle-workplace check (staffed citizens who aren't actually working) ---
  # work_stats.js samples /status a few times (~6s of blocking), so this runs
  # only on the heartbeat cadence, not every poll. Emits an event only when the
  # idle count crosses IDLE_THRESHOLD (quiet under Monitor). idle_n is also fed
  # into the heartbeat line below. timeout + || true so a slow/failed sample
  # never kills the watcher.
  idle_n="-"
  if [ $((n % HEARTBEAT_EVERY)) -eq 1 ]; then
    idle_line=$(cd $DIR && WORK_STATS_BRIEF=1 timeout 45 node work_stats.js 3 3000 2>/dev/null | grep '^BRIEF ' || true)
    if [ -n "$idle_line" ]; then
      idle_n=$(echo "$idle_line" | sed -n 's/^BRIEF total=[0-9]* idle=\([0-9]*\).*/\1/p')
      [ -z "$idle_n" ] && idle_n="-"
      if [ "$idle_n" != "-" ] && [ "$idle_n" -ge "$IDLE_THRESHOLD" ] 2>/dev/null; then
        detail=$(echo "$idle_line" | cut -d' ' -f4- | tr ' ' '\n' | head -6 | tr '\n' ' ')
        echo "IDLE WORKPLACES: $idle_n staffed but not working (${detail%% })"
      fi
    fi
  fi

  # --- hourly heartbeat with audit summary ---
  if [ $((n % HEARTBEAT_EVERY)) -eq 1 ]; then
    audit=$(cd $DIR && node verify_suite.js 2>/dev/null | sed -n 2p)
    echo "HEARTBEAT: citizens=$citizens sick=$sick starving=$starving | $audit | idle-workplaces=$idle_n"
  fi

  sleep $POLL
done
