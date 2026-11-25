#!/usr/bin/env bash
# Starts the whole system for a live demo (Linux/macOS/Git Bash). Services run in the
# background with logs in data/logs/; the Monitor runs in this terminal. Ctrl+C stops everything.
#   scripts/run-all.sh [time-scale=10] [emergency-aircraft]
set -euo pipefail
cd "$(dirname "$0")/.."
SCALE="${1:-10}"
EMERGENCY="${2:-}"
[ -f target/atc.jar ] || mvn -q -DskipTests package
mkdir -p data/logs
rm -f data/allocator-log.jsonl   # each demo starts with an empty allocator log
PIDS=()
start() { # name, java args...
  local name="$1"; shift
  java -cp target/atc.jar "$@" > "data/logs/$name.log" 2>&1 &
  PIDS+=("$!")
  echo "started $name (pid $!)"
}
cleanup() { echo "stopping..."; kill "${PIDS[@]}" 2>/dev/null || true; wait 2>/dev/null || true; }
trap cleanup EXIT INT TERM

start allocator atc.allocator.RunwayAllocator --time-scale "$SCALE"
sleep 2
start control-YYZ atc.control.ControlService --airport YYZ
start control-YOW atc.control.ControlService --airport YOW
sleep 2
if [ -n "$EMERGENCY" ]; then
  start sim-YYZ atc.aircraft.AircraftSimulator --airport YYZ --time-scale "$SCALE" --emergency "$EMERGENCY"
else
  start sim-YYZ atc.aircraft.AircraftSimulator --airport YYZ --time-scale "$SCALE"
fi
start sim-YOW atc.aircraft.AircraftSimulator --airport YOW --time-scale "$SCALE" --seed 2
echo "allocator pid: ${PIDS[0]}  (crash test from another terminal: kill -9 ${PIDS[0]})"
sleep 1
java -cp target/atc.jar atc.monitor.Monitor --events-log data/logs/monitor-events.log
