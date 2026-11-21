# Empirical Benchmark Report: In-Process CQRS Architecture

> **Hardware:** 4 vCPU, 16 GB RAM (GitHub Codespaces `standardLinux32gb`)  
> **Load Generator:** Grafana **k6** (10 $\to$ 50 $\to$ 100 VUs across 3 scenarios)  
> **Telemetry:** **Telegraf** (5-second sampling: Host CPU/RAM, JVM Heap, GC Pauses, HikariCP, PostgreSQL)  
> **Baseline (`--before`):** `595c5b2` (Monolithic `DiagramService` + Actuator/Prometheus)  
> **Target (`--after`):** `feat/cqrs` (In-Process CQRS: `DiagramCommandService` & `DiagramQueryService`)  
> **Benchmark Artifacts (GitHub Gist):** [DiagramIO Phase 2 CQRS Benchmark Raw Results & Scripts](https://gist.github.com/MihnixArch0n/ca4adaff80b8c8e49a17b8add9e069b1)

---

## 1. Benchmark Results (Before vs After)

### 1.1. Scenario A: Realistic Traffic Split (80% Read / 20% Write, 5m)

| Metric | Before (`595c5b2`) | After (`feat/cqrs`) | Delta |
|---|---|---|---|
| **Throughput (RPS)** | 493.67 req/s | **497.49 req/s** | +0.77% |
| **Total Requests** | 148,162 | **149,320** | +1,158 |
| **Average Latency** | 45.84 ms | **45.10 ms** | -0.74 ms |
| **Median Latency ($p50$)** | 28.95 ms | **29.21 ms** | +0.26 ms |
| **90th Percentile ($p90$)** | 111.50 ms | **108.50 ms** | -3.00 ms |
| **95th Percentile ($p95$)** | 150.42 ms | **146.71 ms** | -2.5% |
| **Error Rate** | 0.013% | **0.016%** | Stable |
| **$p95$ Query List** | 136.00 ms | **135.82 ms** | -0.1% |
| **$p95$ Query Search** | 162.68 ms | **158.23 ms** | -2.7% |
| **$p95$ Query Detail** | 107.46 ms | **107.02 ms** | -0.4% |
| **$p95$ Command Create** | 169.25 ms | **166.53 ms** | -1.6% |
| **$p95$ Command Update** | 193.44 ms | **178.46 ms** | -7.7% |

### 1.2. Scenario B1: Query Saturation (100% Query List, 5m)

| Metric | Before (`595c5b2`) | After (`feat/cqrs`) | Delta |
|---|---|---|---|
| **Throughput (RPS)** | 897.45 req/s | **903.96 req/s** | +0.73% |
| **Total Requests** | 269,345 | **271,266** | +1,921 |
| **Average Latency** | 52.79 ms | **52.43 ms** | -0.36 ms |
| **Median Latency ($p50$)** | 32.96 ms | **32.94 ms** | -0.02 ms |
| **90th Percentile ($p90$)** | 122.83 ms | **121.67 ms** | -1.16 ms |
| **95th Percentile ($p95$)** | 168.01 ms | **165.58 ms** | -1.4% |
| **Error Rate** | 0.000% | **0.000%** | 0 errors |

### 1.3. Scenario B2: Read/Write Contention (80 Read VUs + 20 Write VUs, 4m)

| Metric | Before (`595c5b2`) | After (`feat/cqrs`) | Delta |
|---|---|---|---|
| **Throughput (RPS)** | 911.80 req/s | **927.31 req/s** | +1.70% |
| **Total Requests** | 218,923 | **222,653** | +3,730 |
| **Average Latency** | 55.15 ms | **53.72 ms** | -1.43 ms |
| **Median Latency ($p50$)** | 41.16 ms | **40.29 ms** | -0.87 ms |
| **Read $p95$ (80 VUs)** | 152.16 ms | **149.36 ms** | -1.8% |
| **Write $p95$ (20 VUs)** | 220.03 ms | **205.87 ms** | -6.4% |
| **Error Rate** | 0.037% | **0.049%** | Stable under contention |

---

## 2. System Telemetry (Telegraf 5s Sampling)

| Metric | Before (`595c5b2`) | After (`feat/cqrs`) | Delta / Notes |
|---|---|---|---|
| **Host CPU** | 86.18% avg (max 99.70%) | **86.39% avg (max 99.65%)** | Balanced 4-vCPU saturation |
| **Host RAM Used** | 2,159.87 MB avg | **2,186.17 MB avg** | Flat memory profile |
| **App Container RAM** | 596.39 MB avg (peak 722.69 MB) | **602.47 MB avg (peak 665.88 MB)** | -7.9% peak RAM |
| **JVM Old Gen Heap** | 53.18 MB avg (peak 57.24 MB) | **56.20 MB avg (peak 60.53 MB)** | Lean heap footprint |
| **JVM GC Pause (max)** | 11.71 ms avg (max 21.00 ms) | **11.01 ms avg (max 17.00 ms)** | Sub-20ms pauses |
| **HikariCP Active Pool** | 7.24 avg / 10 max | **7.45 avg / 10 max** | Healthy pool utilization |
| **PostgreSQL CPU** | 176.74% avg | **178.20% avg (peak 261.63%)** | Comparable DB CPU load |
| **PostgreSQL RAM** | 92.89 MB avg | **78.72 MB avg** | -15.3% DB RAM |
| **PostgreSQL Buffer Hit** | 100.0% | **100.0%** | Full buffer pool hit |
| **DB Commits / Deadlocks** | 1,232,651 / 0 | **1,244,114 / 0** | 0 rollbacks, 0 deadlocks |

### 2.1. Raw Telemetry & Benchmark Data (GitHub Gist)

* **Benchmark Artifacts Gist:** [https://gist.github.com/MihnixArch0n/ca4adaff80b8c8e49a17b8add9e069b1](https://gist.github.com/MihnixArch0n/ca4adaff80b8c8e49a17b8add9e069b1)
  * `before-scenario-a-summary.json` & `after-scenario-a-summary.json`: k6 load test results and percentiles for Scenario A (80/20 traffic split).
  * `before-scenario-b1-summary.json` & `after-scenario-b1-summary.json`: k6 load test results for Scenario B1 (100% query saturation).
  * `before-scenario-b2-summary.json` & `after-scenario-b2-summary.json`: k6 load test results for Scenario B2 (read/write contention).
  * `before-telemetry-stats.json` / `after-telemetry-stats.json` & `before-telemetry-summary.txt` / `after-telemetry-summary.txt`: Aggregated Telegraf container, JVM, HikariCP, and PostgreSQL statistics.
  * `before-telegraf.out` & `after-telegraf.out`: Raw 5-second interval Telegraf telemetry streams during test runs.
  * `runner.log`: Automated benchmark suite execution log.
  * `parse-telegraf.py` & `compare_results.py`: Telemetry parser and before/after comparison scripts.

---

## 3. Conclusions

- **Zero Throughput Overhead:** Splitting `DiagramService` into `DiagramCommandService` and `DiagramQueryService` preserves throughput (+0.73% to +1.70% RPS) and improves tail latency across all scenarios (-1.4% to -7.7% $p95$).
- **Lean Memory & GC:** Both baseline and CQRS maintain ~53–56 MB Old Gen Heap avg and sub-20ms GC pauses under 100 VUs, with CQRS reducing app peak RAM (-7.9%) and PostgreSQL RAM (-15.3%).
- **Clean Architectural Seam:** Verified by ArchUnit tests, isolating read projections from write invariants so downstream caching can target `DiagramQueryService` directly.
