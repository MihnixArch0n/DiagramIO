# Telegraf Monitoring for k6 Benchmarks

Dedicated Compose setup to collect host metrics (CPU per-core/total, Memory, System Load) and container metrics (`diagramio_app`, `diagramio_db`, `k6`) during k6 load testing.

---

## Usage

### 1. Start Telegraf
```bash
docker compose -f benchmarks/data-storage/monitoring/docker-compose.telegraf.yml up -d
```

### 2. Run k6 Benchmark
```bash
docker run --rm -i --network=host \
  --name diagramio_k6 \
  -v "$(pwd)/benchmarks/data-storage/scripts:/scripts:ro" \
  grafana/k6:latest run \
  --vus 25 --duration 10s \
  /scripts/data-listing.js
```

### 3. Stop Telegraf
```bash
docker compose -f benchmarks/data-storage/monitoring/docker-compose.telegraf.yml down
```

---

## Output Metrics

* **Log file**: `benchmarks/data-storage/monitoring/metrics/loadtest-metrics.out` (JSON format)
* **Live Prometheus endpoint**: `http://localhost:9273/metrics`
