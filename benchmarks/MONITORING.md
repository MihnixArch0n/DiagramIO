# Telegraf Monitoring for Benchmarks

Dedicated Telegraf setup to collect host metrics (CPU, Memory, System Load), container metrics (`diagramio_app`, `diagramio_db`), Spring Actuator Prometheus, and PostgreSQL engine metrics during k6 load testing.

## Usage

### 1. Start Telegraf
Start the monitoring container for your target benchmark module:
```bash
# For CQRS benchmark:
docker compose -f benchmarks/cqrs/monitoring/docker-compose.telegraf.yml up -d

# For Data Storage benchmark:
docker compose -f benchmarks/data-storage/monitoring/docker-compose.telegraf.yml up -d
```

### 2. Stop Telegraf
```bash
# For CQRS benchmark:
docker compose -f benchmarks/cqrs/monitoring/docker-compose.telegraf.yml down

# For Data Storage benchmark:
docker compose -f benchmarks/data-storage/monitoring/docker-compose.telegraf.yml down
```

## Metrics Output

* **Metrics log file**: `benchmarks/<module>/monitoring/metrics/loadtest-metrics.out` (JSON format)
* **Live Prometheus endpoint**: `http://localhost:9273/metrics`
