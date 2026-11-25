#!/usr/bin/env bash
# Builds (if needed) and runs the evaluation scenarios; results go to results/<scenario>/.
#   scripts/run-scenarios.sh            all 7 scenarios (~3 min)
#   scripts/run-scenarios.sh 1 5        selected scenarios
#   scripts/run-scenarios.sh 7 --quick  short load test
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f target/atc.jar ] || mvn -q -DskipTests package
exec java -cp target/atc.jar atc.scenario.ScenarioRunner "$@"
