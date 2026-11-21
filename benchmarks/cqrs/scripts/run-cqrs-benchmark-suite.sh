#!/usr/bin/env bash
set -e

MODE=""
QUICK_MODE="false"
SCENARIO="all"

while [[ $# -gt 0 ]]; do
  case $1 in
    --before)
      MODE="before"
      shift
      ;;
    --after)
      MODE="after"
      shift
      ;;
    --quick)
      QUICK_MODE="true"
      shift
      ;;
    --full)
      QUICK_MODE="false"
      shift
      ;;
    --scenario)
      SCENARIO="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1"
      echo "Usage: $0 --before | --after [--quick | --full] [--scenario A | B1 | B2 | all]"
      exit 1
      ;;
  esac
done

if [ -z "$MODE" ]; then
    echo "Usage: $0 --before | --after [--quick | --full] [--scenario A | B1 | B2 | all]"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCHMARK_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$(dirname "$BENCHMARK_DIR")")"
cd "$ROOT_DIR"

SCRIPTS_DIR="$BENCHMARK_DIR/scripts"
RESULTS_DIR="${RESULTS_DIR:-$BENCHMARK_DIR/results}"
MONITORING_DIR="$BENCHMARK_DIR/monitoring"
mkdir -p "$RESULTS_DIR" "$MONITORING_DIR/metrics"
chmod 777 "$RESULTS_DIR" "$MONITORING_DIR/metrics" 2>/dev/null || true

# Auto-detect accessible docker/podman socket for rootless and standard setups
if [ -z "$DOCKER_SOCK" ]; then
    if [ -n "$XDG_RUNTIME_DIR" ] && [ -S "$XDG_RUNTIME_DIR/podman/podman.sock" ]; then
        export DOCKER_SOCK="$XDG_RUNTIME_DIR/podman/podman.sock"
    elif [ -n "$XDG_RUNTIME_DIR" ] && [ -S "$XDG_RUNTIME_DIR/docker.sock" ]; then
        export DOCKER_SOCK="$XDG_RUNTIME_DIR/docker.sock"
    elif [ -S "/var/run/docker.sock" ] && [ -r "/var/run/docker.sock" ]; then
        export DOCKER_SOCK="/var/run/docker.sock"
    fi
fi

PREFIX="${MODE}"
if [ "$QUICK_MODE" = "true" ]; then
    PREFIX="${PREFIX}-quick"
fi

BASELINE_COMMIT="595c5b2"
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"

if [ "$MODE" = "before" ]; then
    echo "=========================================================="
    echo " [1/7] Preparing Before Environment (commit $BASELINE_COMMIT)..."
    echo "=========================================================="
    rm -f "$RESULTS_DIR"/${PREFIX}*
    rm -f "$MONITORING_DIR/metrics/"* 2>/dev/null || true
    
    # Preserve benchmark folder across checkout
    TMP_BENCH="/tmp/diagramio-cqrs-bench-backup"
    rm -rf "$TMP_BENCH"
    cp -r "$BENCHMARK_DIR" "$TMP_BENCH"
    
    git checkout "$BASELINE_COMMIT"
    
    # Restore benchmark folder
    rm -rf "$BENCHMARK_DIR"
    cp -r "$TMP_BENCH" "$BENCHMARK_DIR"
    chmod -R a+rX "$BENCHMARK_DIR" 2>/dev/null || true
    chmod -R 777 "$RESULTS_DIR" "$MONITORING_DIR/metrics" 2>/dev/null || true
else
    echo "=========================================================="
    echo " [1/7] Preparing After Environment (In-Process CQRS)..."
    echo "=========================================================="
    rm -f "$RESULTS_DIR"/${PREFIX}*
    rm -f "$MONITORING_DIR/metrics/"* 2>/dev/null || true
    chmod -R 777 "$RESULTS_DIR" "$MONITORING_DIR/metrics" 2>/dev/null || true
fi

echo "[2/7] Cleaning up existing containers and volumes..."
docker compose down -v 2>/dev/null || true
docker compose -f "$MONITORING_DIR/docker-compose.telegraf.yml" down 2>/dev/null || true
docker rm -f diagramio_k6 2>/dev/null || true

echo "[3/7] Building and starting application stack..."
docker compose up -d --build

echo "Waiting for http://localhost:8080/api/v1/health to become ready..."
for i in $(seq 1 60); do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/health 2>/dev/null || true)
    if [ "$STATUS" = "200" ]; then
        echo " Application is UP and HEALTHY!"
        break
    fi
    sleep 2
    echo -n "."
done

echo "[4/7] Starting Telegraf monitoring container..."
docker compose -f "$MONITORING_DIR/docker-compose.telegraf.yml" up -d
sleep 2

SEED_COUNT="150"
if [ "$QUICK_MODE" = "true" ]; then
    SEED_COUNT="30"
fi

echo "[5/7] Seeding realistic test data (accounts, $SEED_COUNT diagrams)..."
docker run --rm -i --network=host -v "$SCRIPTS_DIR:/scripts" grafana/k6 run \
    -e TARGET_URL="http://localhost:8080" \
    -e TARGET_COUNT="$SEED_COUNT" \
    /scripts/seed-data.js

echo "[6/7] Running CQRS k6 benchmark ($SCENARIO, quick=$QUICK_MODE)..."

run_k6_scenario() {
    local SCEN_NAME="$1"
    local SCRIPT_FILE="$2"
    echo "----------------------------------------------------------"
    echo " Running Scenario $SCEN_NAME: $SCRIPT_FILE..."
    echo "----------------------------------------------------------"
    docker rm -f diagramio_k6 2>/dev/null || true
    docker run --rm -i --network=host --name diagramio_k6 \
        -v "$SCRIPTS_DIR:/scripts" -v "$RESULTS_DIR:/results" \
        grafana/k6 run \
        -e TARGET_URL="http://localhost:8080" \
        -e QUICK="$QUICK_MODE" \
        --summary-export="/results/${PREFIX}-${SCEN_NAME}-summary.json" \
        "/scripts/${SCRIPT_FILE}" || true
}

if [ "$SCENARIO" = "A" ] || [ "$SCENARIO" = "all" ]; then
    run_k6_scenario "scenario-a" "cqrs-scenario-a-traffic-split.js"
fi

if [ "$SCENARIO" = "B1" ] || [ "$SCENARIO" = "all" ]; then
    run_k6_scenario "scenario-b1" "cqrs-scenario-b1-query-saturation.js"
fi

if [ "$SCENARIO" = "B2" ] || [ "$SCENARIO" = "all" ]; then
    run_k6_scenario "scenario-b2" "cqrs-scenario-b2-contention.js"
fi

echo "[7/7] Stopping Telegraf and collecting metrics..."
docker compose -f "$MONITORING_DIR/docker-compose.telegraf.yml" down 2>/dev/null || true

if [ -f "$MONITORING_DIR/metrics/loadtest-metrics.out" ]; then
    cp "$MONITORING_DIR/metrics/loadtest-metrics.out" "$RESULTS_DIR/${PREFIX}-telegraf.out"
    echo "Saved raw metrics to $RESULTS_DIR/${PREFIX}-telegraf.out"
    if [ -f "$SCRIPTS_DIR/parse-telegraf.py" ]; then
        python3 "$SCRIPTS_DIR/parse-telegraf.py" "$RESULTS_DIR/${PREFIX}-telegraf.out" "$RESULTS_DIR/${PREFIX}-telemetry-stats.json" > "$RESULTS_DIR/${PREFIX}-telemetry-summary.txt" 2>/dev/null || true
        echo "Telemetry summary saved to $RESULTS_DIR/${PREFIX}-telemetry-stats.json and ${PREFIX}-telemetry-summary.txt"
    fi
fi

echo "=========================================================="
echo " CQRS Benchmark run completed for: ${PREFIX}"
echo " Results stored in: $RESULTS_DIR"
echo "=========================================================="

if [ "$MODE" = "before" ] && [ -n "$CURRENT_BRANCH" ] && [ "$CURRENT_BRANCH" != "HEAD" ]; then
    echo "Switching back to $CURRENT_BRANCH..."
    TMP_BENCH="/tmp/diagramio-cqrs-bench-backup"
    rm -rf "$TMP_BENCH"
    cp -r "$BENCHMARK_DIR" "$TMP_BENCH"
    git checkout -f "$CURRENT_BRANCH"
    rm -rf "$BENCHMARK_DIR"
    cp -r "$TMP_BENCH" "$BENCHMARK_DIR"
    chmod -R a+rX "$BENCHMARK_DIR" 2>/dev/null || true
    chmod -R 777 "$RESULTS_DIR" "$MONITORING_DIR/metrics" 2>/dev/null || true
fi
