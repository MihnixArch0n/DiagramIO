#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=== Running k6 via Docker (grafana/k6) ==="
docker run --rm -i --network=host -v "$SCRIPT_DIR:/scripts" grafana/k6 "$@"
