# Distributed Air Traffic Control System

SOFE4790U Distributed Systems, Group 6, Ontario Tech University, Fall 2026.

A distributed runway- and taxiway-allocation system for two airports (YYZ and YOW). Every
component is a separate process communicating over ZeroMQ. The design centres on the classic
distributed-systems problems: a single serializing authority, idempotent retries, durable
write-ahead logging, crash recovery with reconciliation, and failure detection with heartbeats.

> Educational simulation with synthetic data. These are not real aviation procedures, and
> the runway layouts only approximate the real airports: YYZ uses Pearson's five runways and YOW
> Ottawa's three, with rounded lengths (YOW 04/22 is lengthened to 1600 m so small aircraft can use it).

---

## Contents
1. [Architecture](#architecture)
2. [Prerequisites](#prerequisites)
3. [Build and test](#build-and-test)
4. [Quick start: the full demo](#quick-start-the-full-demo)
5. [Running each component by hand](#running-each-component-by-hand)
6. [Evaluation scenarios](#evaluation-scenarios)
7. [Configuration](#configuration)
8. [Message contract (summary)](#message-contract-summary)
9. [Correctness invariants](#correctness-invariants)
10. [Project layout](#project-layout)
11. [Troubleshooting](#troubleshooting)

---

## Architecture

```
        AircraftSimulator (YYZ)                         AircraftSimulator (YOW)
   DEALER per aircraft │  PUB positions            DEALER per aircraft │  PUB positions
                       ▼       ▼                                       ▼       ▼
             ┌──────────────────────────┐                   ┌──────────────────────────┐
             │   ControlService  YYZ    │                   │   ControlService  YOW    │
             │ ROUTER :5560  SUB :5561  │                   │ ROUTER :5570  SUB :5571  │
             │ taxiways, aircraft view  │                   │ taxiways, aircraft view  │
             │ PUB events :5562         │                   │ PUB events :5572         │
             └──────┬──────────▲────────┘                   └──────┬──────────▲────────┘
     DEALER [key,msg]│          │ SUB (heartbeats, BUMPED)   DEALER │          │ SUB
                     ▼          │                                  ▼          │
             ┌─────────────────────────────────────────────────────────────────────┐
             │                  RunwayAllocator (single authority)                 │
             │   ROUTER :5555   PUB :5556   one thread owns all state             │
             │   append-only JSON-lines log, fsync before every reply             │
             └─────────────────────────────────────────────────────────────────────┘
                                         ▲ SUB (all PUB sockets)
                                  ┌──────┴──────┐
                                  │   Monitor   │  console dashboard, listen-only
                                  └─────────────┘
```

| Process | Main class | Responsibility |
|---|---|---|
| RunwayAllocator | `atc.allocator.RunwayAllocator` | Single authority for runway reservations at both airports. Processes requests one at a time, checks eligibility and buffer-time conflicts, deduplicates by `requestId`, writes the log before replying, and rebuilds state from the log on restart. |
| ControlService (×2) | `atc.control.ControlService` | One per airport. Validates aircraft requests, forwards them to the allocator asynchronously, assigns taxiways, relays instructions, tracks acks, and filters out-of-order positions. Allocator silent → `HELD` (never "rejected"); reconciles after an allocator restart. |
| AircraftSimulator (×2) | `atc.aircraft.AircraftSimulator` | Runs N aircraft for one airport: phase state machine, position updates with sequence numbers, requests retried with the same `requestId` (lazy pirate), acks, completion. |
| Monitor | `atc.monitor.Monitor` | Refreshing console dashboard of aircraft, runways, taxiways, instructions and service health. |
| WebDashboard | `atc.monitor.WebDashboard` | The same listen-only data in a browser at http://localhost:8080: an interactive 3D airfield (runways, terminal, tower and every aircraft placed from its live position, phase and assigned runway; drag to orbit, Ctrl + scroll to zoom), flight strips, service health, airspace profiles, per-runway slot timelines, aircraft tables, taxiways and the event log. An Evaluation tab shows the scenario checks and load-test results. |
| ScenarioRunner | `atc.scenario.ScenarioRunner` | Launches the real processes, runs the 7 evaluation scenarios and writes PASS/FAIL plus CSV metrics. |

Default ports (YOW = YYZ + 10; the scenarios add 1000 to every port):

| Component | Socket | Port |
|---|---|---|
| RunwayAllocator | ROUTER (requests) / PUB (heartbeats, events, metrics) | 5555 / 5556 |
| ControlService YYZ | ROUTER (aircraft) / SUB (positions) / PUB (events) | 5560 / 5561 / 5562 |
| ControlService YOW | same | 5570 / 5571 / 5572 |

---

## Prerequisites

- **Java 17** (tested with Temurin 17.0.21)
- **Maven 3.9+** (tested with 3.9.9)
- Windows PowerShell 5.1+ for the `.ps1` scripts, or bash (Linux, macOS, Git Bash) for the `.sh` scripts

There is nothing else to install. JeroMQ is pure Java, and there is no database: the allocator
persists to a JSON-lines file.

---

## Build and test

```
mvn -q package               # compiles, runs all 98 unit/integration tests (15-60 s), builds target/atc.jar
mvn -q -DskipTests package   # just the jar
```

`target/atc.jar` contains every dependency. Every component runs from it:

```
java -cp target/atc.jar atc.<package>.<MainClass> [args]
```

The POM forces UTF-8 (`project.build.sourceEncoding`), so it builds the same on machines whose
default encoding is Cp1252.

---

## Quick start: the full demo

### Windows (PowerShell)
```
scripts\run-all.ps1                       # allocator, 2 control services, 2 simulators (7 aircraft each), Monitor + web dashboard
scripts\run-all.ps1 -Emergency YYZ-AC3    # same, with YYZ-AC3 declaring an emergency on landing
scripts\allocator-crash.ps1               # hard-kill the allocator, wait 8 s, restart it from its log
scripts\stop-all.ps1                      # stop everything run-all started (and nothing else)
```
Each service opens in its own window. Logs are also written to `data\logs\`. Every demo starts
with a fresh allocator log (`-KeepLog` continues the previous one). The demo runs at 10× speed
(`-TimeScale 10`): a 60 s runway slot lasts 6 s.

### Linux / macOS / Git Bash
```
scripts/run-all.sh [time-scale] [emergency-aircraft]    # services in the background, Monitor in this terminal
kill -9 <allocator pid printed at start>                 # crash test, from another terminal
# restart it from its log:
java -cp target/atc.jar atc.allocator.RunwayAllocator --time-scale 10
```
Ctrl+C stops everything.

### What to watch on the Monitor
Open **http://localhost:8080** for the web dashboard (started by `run-all`), or watch the console Monitor; both show the same data.
- **Normal operation:** arrivals go APPROACH → LANDING → TAXI_IN → PARKED; departures go PARKED →
  TAXI_OUT → HOLDING_SHORT → TAKEOFF_ROLL → DEPARTED. Runways show `IN USE` / `booked … in Ns` /
  `free`; taxiways show which aircraft holds them.
- **After `allocator-crash`:** within ~3 s the allocator shows `DOWN`, both control services show
  `allocator reachable: NO`, and new requests are `HELD` (never assigned). On restart: `RECONCILE`
  and `RECONCILED` events, held requests become `ASSIGNED`, and traffic resumes.
- Afterwards, verify the invariants over the whole run from the durable log:
  `java -cp target/atc.jar atc.allocator.CheckLog data/allocator-log.jsonl --time-scale 10`

---

## Running each component by hand

Start them in this order, each in its own terminal (the order is not strictly required: ZeroMQ
reconnects and control services hold requests until the allocator appears).

```
# 1. Allocator. Log defaults to data/allocator-log.jsonl.
java -cp target/atc.jar atc.allocator.RunwayAllocator [--time-scale 10] [--log file] [--config file]
                                                      [--router-port 5555] [--pub-port 5556]

# 2. One control service per airport. --timeout-ms: allocator reply timeout (default 30000 from config).
java -cp target/atc.jar atc.control.ControlService --airport YYZ [--timeout-ms 2000] [--allocator-host host]
java -cp target/atc.jar atc.control.ControlService --airport YOW

# 3. Monitor. --no-clear for terminals without ANSI; --once prints one frame and exits.
java -cp target/atc.jar atc.monitor.Monitor [--no-clear] [--once] [--events-log file] [--host host]

# 3b. Web dashboard (optional), then open http://localhost:8080. --bind 0.0.0.0 to reach it from other machines.
java -cp target/atc.jar atc.monitor.WebDashboard [--port 8080] [--bind 127.0.0.1] [--host host] [--results dir]

# 4. Aircraft. --time-scale must match the allocator's.
java -cp target/atc.jar atc.aircraft.AircraftSimulator --airport YYZ --time-scale 10
     [--count 7] [--cycles N] [--seed 1] [--sizes SMALL,MEDIUM,HEAVY] [--emergency YYZ-AC3]
     [--retry-ms n] [--control-host host]
```

Useful options:
- `--cycles 1` makes every aircraft perform one runway operation and then the simulator exits.
  `--count 2 --cycles 1` gives exactly one landing (AC1) and one takeoff (AC2).
- `--sizes HEAVY` at YOW demonstrates rejection (YOW has no runway long enough).
- `--unsafe-no-fsync` on the allocator turns off durability. It exists only for the load-test
  comparison and breaks invariant 5.

To run across machines: all servers bind on every interface. Point each control service at the
allocator with `--allocator-host`, and each simulator at its control service with `--control-host`.
The Monitor's `--host` assumes the allocator and both control services are on that one host.

---

## Evaluation scenarios

```
scripts\run-scenarios.ps1            # Windows: all 7 scenarios (~2 min)
scripts/run-scenarios.sh             # bash
scripts\run-scenarios.ps1 1 5        # selected scenarios
scripts\run-scenarios.ps1 7 --quick  # short load test (100 and 500 req/s, 3 s each)
# equivalent: java -cp target/atc.jar atc.scenario.ScenarioRunner [all|1..7] [--quick]
#             [--rates 100,250,500,1000,2000] [--seconds 5] [--no-fsync-compare] [--out results]
```

The runner starts the real components as separate OS processes on ports +1000 (so it does not
disturb a running demo), drives them as scripted aircraft and control clients, and checks the
invariants against the allocator's durable log. Nothing else may be using ports 6555–6572.
The exit code is 0 only if every check passed.

| # | Scenario | What it shows |
|---|---|---|
| 1 | 40 aircraft request at the same instant | no overlapping or ineligible reservation (inv. 1, 2); end-to-end decision latency |
| 2 | HEAVY aircraft at YOW (no long runway) | `REJECTED` with a reason, via the control service and directly; retry gets the same answer |
| 3 | Duplicate requests, lost reply + retry, duplicate ACKs | identical answers, exactly one decision per `requestId` (inv. 3) |
| 4 | Shuffled, duplicated and delayed position updates | exactly the stale ones dropped; newest position kept (inv. 4) |
| 5 | Allocator **hard-killed** mid-run and restarted | replies were durable (inv. 5); unacked bookings come back `UNCERTAIN` and are reconciled (inv. 6); no assignments during the outage (inv. 7) |
| 6 | Emergency with a pending (unacked) booking in the way | emergency gets the earliest slot; the bumped aircraft is told to hold and rebooked; acked bookings untouched |
| 7 | Load test at 100–2000 req/s, with and without fsync | throughput, p50/p95/p99/max latency, allocator CPU and heap |

### Reading the results
- `results/summary.csv`: every check of the **latest** run (scenario, invariant, check, PASS/FAIL,
  detail). Each run replaces the folders of the scenarios it ran.
- `results/<scenario>/checks.txt`: that scenario's checks plus measured numbers.
- `results/<scenario>/*.csv`: per-request data (`requests.csv`, `replies.csv`, `sequence.csv`,
  `timeline.csv`); for scenario 7, `summary.csv` (one row per mode and rate) and
  `latencies-<mode>-<rate>.csv` (every request).
- `results/<scenario>/*.log`: the log of every process the scenario started, plus `config.json`.
- `results/sample/scenarios/` is committed: a compact copy of a full run of all seven scenarios,
  so the results can be inspected without running anything. Everything else under `results/` is
  regenerated and git-ignored.
- The web dashboard's **Evaluation** tab (http://localhost:8080/#evaluation) shows the latest run in
  `results/` (or the committed sample if there is none) as tables and charts.

Reference results (this machine): all 71 checks PASS. The allocator sustains ~1,200
decisions/s with fsync (~1,600 without). At 1000 req/s, p99 latency is about 12 ms with fsync
and 6 ms without. The full analysis is in the final report.

---

## Configuration

`config/airports.json` (synthetic data):

```jsonc
{
  "allocator": { "host": "localhost", "routerPort": 5555, "pubPort": 5556 },
  "timing": {
    "slotSeconds": 60,            // runway occupancy per operation
    "bufferSeconds": 30,          // separation before AND after every slot
    "waitThresholdSeconds": 300,  // earliest slot further out than this -> WAIT
    "receiveTimeoutMs": 30000,    // request/reply timeout (timeout = HELD/UNCERTAIN, never rejected)
    "heartbeatMs": 1000,          // heartbeat period; DOWN after 3 missed
    "timeScale": 1                // simulated seconds per real second (or --time-scale n)
  },
  "airports": [
    { "id": "YYZ", "controlRouterPort": 5560, "positionSubPort": 5561, "eventPubPort": 5562,
      "aircraftCount": 7, "taxiways": ["YYZ-A", "..."],
      "runways": [ { "id": "YYZ-05/23", "lengthM": 3389 }, { "id": "YYZ-06L/24R", "lengthM": 2956 }, "..." ] }
  ]
}
```

- **Size classes:** SMALL needs ≥ 1500 m, MEDIUM ≥ 2500 m, HEAVY ≥ 3200 m. A runway may also
  cap the size class with `maxSizeClass`.
- **Slots:** each operation occupies `[start − buffer, end + buffer)`, and two such intervals on
  one runway never overlap.
- **Time scale:** `timeScale` divides slot, buffer, wait-threshold and aircraft durations, but
  not network timeouts or heartbeats. The allocator and simulators must use the same value.

---

## Message contract (summary)

JSON over ZeroMQ, with the field `type` naming the message. Optional fields are omitted when null.

| Message | Direction | Fields |
|---|---|---|
| `RUNWAY_REQUEST` | aircraft → control | requestId, aircraftId, airportId, operation (LANDING/TAKEOFF), sizeClass, emergency, ts, earliestStart? |
| `INSTRUCTION` | control → aircraft | requestId, aircraftId, status (ASSIGNED/WAIT/REJECTED/HELD), runwayId?, taxiwayId?, slotStart?, slotEnd?, reason? |
| `ACK` / `COMPLETE` | aircraft → control (COMPLETE also control → allocator) | requestId, aircraftId |
| `POSITION_UPDATE` | aircraft → control (pub/sub) | aircraftId, airportId, seq, phase, x, y, altitudeFt, speedKt, ts |
| `RESERVE` | control → allocator | requestId, aircraftId, airportId, operation, sizeClass, emergency, earliestStart? |
| `CONFIRM` / `RELEASE` / `QUERY` | control → allocator | requestId |
| `RESERVATION` | allocator → control | requestId, state, runwayId, slotStart, slotEnd, reason? |
| `HEARTBEAT` | every PUB, 1/s | service, ts, startedAt? (changes on restart) |
| `EVENT` | every PUB | service, kind (STATE, INSTRUCTION, BUMPED, RECONCILE, METRICS, …), detail, ts |
| `ERROR` | reply to bad input | reason |

Reservation states: `PENDING_ACK → CONFIRMED → COMPLETED`, plus `RELEASED`, `BUMPED`,
`UNCERTAIN` (after an allocator restart), and the recorded outcomes `REJECTED` and `WAIT`.
The full contract and the failure-handling rules are described in the final report.

---

## Correctness invariants

Checked by the unit tests, the scenarios, and `CheckLog`:

1. Never two overlapping reservations (including buffers) on one runway.
2. Never a runway assigned to an ineligible aircraft.
3. A retried `requestId` returns the original outcome, never a second reservation.
4. Position updates with `seq` ≤ the last seen for that aircraft are ignored.
5. The allocator replies only after the decision is appended and fsynced to its log.
6. After an allocator restart, state is rebuilt from the log; `PENDING_ACK` reservations become
   `UNCERTAIN` and are reconciled (control services `QUERY`) before being released.
7. While the allocator is unreachable, no control service issues new runway assignments.

---

## Project layout

```
pom.xml                      single Maven project -> target/atc.jar
config/airports.json         airports, runways, taxiways, ports, timing
src/main/java/atc/
  common/                    message records, JSON codec, config, clock, logger, ControlState, ProcessMetrics
  allocator/                 RunwayAllocator (process), AllocatorCore (decisions), ReservationLog (WAL),
                             Eligibility, SlotFinder, InvariantChecker, CheckLog
  control/                   ControlService (process), ControlCore (logic), AllocatorLink (retries, health),
                             TaxiwayManager, AircraftView (seq filter)
  aircraft/                  AircraftSimulator (process), AircraftAgent (state machine), SimParams
  monitor/                   Monitor (process), MonitorState, DashboardRenderer, WebDashboard (browser view;
                             page in src/main/resources/web/index.html)
  scenario/                  ScenarioRunner, scenarios S1–S7, clients, metrics sampler
src/test/java/atc/...        JUnit 5: logic tests with controlled clocks + TCP tests with real sockets
scripts/                     run-all, allocator-crash, stop-all, run-scenarios (.ps1 / .sh)
results/                     scenario outputs (git-ignored except results/sample/scenarios/)
data/                        runtime data: allocator log, demo logs (git-ignored)
```

Each process keeps its logic in a socket-free core class (`AllocatorCore`, `ControlCore`,
`AircraftAgent`, `DashboardRenderer`) that the tests drive directly. The process class only owns
the sockets, and a single thread owns all of a process's sockets and state.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Address already in use` | Another run is still up: `scripts\stop-all.ps1`, or close the old windows. Scenarios need ports 6555–6572 free; the demo needs 5555–5572. |
| Monitor prints garbage like `←[H←[2J` | Old console without ANSI support: use Windows Terminal, or add `--no-clear`. |
| Requests stay `HELD` | The allocator is down or unreachable: check its window or log. They are assigned automatically once it is back. |
| Aircraft never move | The simulator and allocator use different time scales; pass the same `--time-scale` to both. |
| A demo starts with old bookings | Use `run-all` without `-KeepLog`, or delete `data/allocator-log.jsonl`. |
| Scenario run fails | Read `results/<scenario>/checks.txt` and that scenario's process logs in the same folder. |
