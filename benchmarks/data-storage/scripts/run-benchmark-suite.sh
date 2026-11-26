#!/usr/bin/env bash
set -e

MODE="$1"

if [ "$MODE" != "--before" ] && [ "$MODE" != "--after" ]; then
    echo "Usage: $0 --before | --after"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCHMARK_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$(dirname "$BENCHMARK_DIR")")"
cd "$ROOT_DIR"

SCRIPTS_DIR="$BENCHMARK_DIR/scripts"
RESULTS_DIR="$BENCHMARK_DIR/results"
MONITORING_DIR="$BENCHMARK_DIR/monitoring"
mkdir -p "$RESULTS_DIR"
chmod 777 "$RESULTS_DIR"

if [ "$MODE" = "--before" ]; then
    echo "=========================================================="
    echo " [1/7] Preparing Before Environment (commit 0a1a989)..."
    echo "=========================================================="
    rm -f "$RESULTS_DIR"/before*
    rm -f "$MONITORING_DIR/metrics/"*
    
    # Preserve benchmark folder across checkout
    TMP_BENCH="/tmp/diagramio-benchmark-backup"
    rm -rf "$TMP_BENCH"
    cp -r "$BENCHMARK_DIR" "$TMP_BENCH"
    
    git checkout 0a1a989
    
    # Restore benchmark folder
    mkdir -p "$(dirname "$BENCHMARK_DIR")"
    cp -r "$TMP_BENCH" "$BENCHMARK_DIR"
    
    PREFIX="before"
else
    echo "=========================================================="
    echo " [1/7] Preparing After Environment (feature/phase2-data-storage)..."
    echo "=========================================================="
    rm -f "$RESULTS_DIR"/after*
    rm -f "$MONITORING_DIR/metrics/"*
    
    git checkout feature/phase2-data-storage
    PREFIX="after"
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

echo "[5/7] Seeding realistic test data (users, 50KB-1.75MB diagrams, snapshots)..."
docker run --rm -i --network=host -v "$SCRIPTS_DIR:/scripts" grafana/k6 run /scripts/seed-data.js

echo "Measuring initial storage size..."
docker exec diagramio_db psql -U root -d diagram_app -c "SELECT relname, pg_size_pretty(pg_relation_size(relid)) as table_size, pg_size_pretty(pg_total_relation_size(relid)) as total_size FROM pg_catalog.pg_statio_user_tables WHERE relname IN ('diagrams', 'diagram_versions', 'diagram_shares') ORDER BY pg_total_relation_size(relid) DESC;" > "$RESULTS_DIR/${PREFIX}-db-size.txt"
if [ "$MODE" = "--after" ]; then
    echo "Blob storage volume size:" >> "$RESULTS_DIR/${PREFIX}-db-size.txt"
    docker run --rm -v diagramio_app_blobs:/data alpine du -sh /data >> "$RESULTS_DIR/${PREFIX}-db-size.txt" 2>/dev/null || true
fi

echo "[6/7] Running k6 benchmark (5 stages: 10 -> 50 -> 100 VUs, 5 minutes total)..."
docker rm -f diagramio_k6 2>/dev/null || true
docker run --rm -i --network=host --name diagramio_k6 -v "$SCRIPTS_DIR:/scripts" -v "$RESULTS_DIR:/results" grafana/k6 run \
    --summary-export="/results/${PREFIX}-summary.json" \
    /scripts/data-listing.js || true

echo "[7/7] Stopping Telegraf and collecting metrics..."
docker compose -f "$MONITORING_DIR/docker-compose.telegraf.yml" down 2>/dev/null || true

if [ -f "$MONITORING_DIR/metrics/loadtest-metrics.out" ]; then
    cp "$MONITORING_DIR/metrics/loadtest-metrics.out" "$RESULTS_DIR/${PREFIX}-telegraf.out"
    echo "Saved raw metrics to $RESULTS_DIR/${PREFIX}-telegraf.out"
fi

echo "Measuring final storage size..."
docker exec diagramio_db psql -U root -d diagram_app -c "SELECT relname, pg_size_pretty(pg_relation_size(relid)) as table_size, pg_size_pretty(pg_total_relation_size(relid)) as total_size FROM pg_catalog.pg_statio_user_tables WHERE relname IN ('diagrams', 'diagram_versions', 'diagram_shares') ORDER BY pg_total_relation_size(relid) DESC;" >> "$RESULTS_DIR/${PREFIX}-db-size.txt"
if [ "$MODE" = "--after" ]; then
    echo "Final blob storage volume size:" >> "$RESULTS_DIR/${PREFIX}-db-size.txt"
    docker run --rm -v diagramio_app_blobs:/data alpine du -sh /data >> "$RESULTS_DIR/${PREFIX}-db-size.txt" 2>/dev/null || true
fi

echo "=== ${MODE} BENCHMARK COMPLETE ==="
