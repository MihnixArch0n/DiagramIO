# In-Process CQRS Benchmark Suite

This directory contains the k6 load testing suite, continuous container monitoring, and benchmark scripts to validate the **In-Process CQRS** architecture (`DiagramCommandService` & `DiagramQueryService`, ADR-0002) against the pre-CQRS monolithic `DiagramService`.

---

## 1. Objectives & Quality Attributes

* **Maintainability & Separation of Concerns:** Decouple mutating business policy (domain invariants, optimistic locking, CAS blob storage) from high-throughput read projections.
* **Latency Isolation & Concurrency:** Ensure read query latencies remain flat ($p95 < 40\text{ ms}$) without being slowed down by concurrent heavy write locks or I/O.
* **Query Saturation & JVM Footprint:** Maximize query throughput and eliminate heap memory bloat through lightweight JPA projections.
* **Deep Observability & Telemetry:** Full continuous monitoring of JVM Heap (Old Gen), GC Pauses, Live Threads, HikariCP connection pool (`active`, `idle`, `pending`, `acquire_time`), PostgreSQL metrics (`numbackends`, `xact_commit`, `xact_rollback`, `deadlocks`, buffer hit ratio), and physical Disk I/O (IOPS, bandwidth).

---

## 2. Directory Structure

```text
benchmarks/cqrs/
├── README.md               # Execution guide & scenario documentation
├── scripts/                # k6 scenarios, runner, seeding, telemetry parser
│   ├── seed-data.js        # Seeds 150+ realistic diagrams (~50KB-1MB payloads)
│   ├── cqrs-scenario-a-traffic-split.js      # Scenario A: 80% Read / 20% Write
│   ├── cqrs-scenario-b1-query-saturation.js  # Scenario B1: 100% Query Saturation
│   ├── cqrs-scenario-b2-contention.js        # Scenario B2: 80 Read VUs + 20 Write VUs
│   ├── run-cqrs-benchmark-suite.sh           # Automated suite runner
│   └── parse-telegraf.py   # Telemetry metric parser (RAM/CPU)
├── monitoring/             # Telegraf container configuration (1s intervals)
└── results/                # Output directory for k6 summary JSONs & telemetry stats
```

---

## 3. Scenarios Overview

### Scenario A: Realistic Traffic Split (80% Read / 20% Write)
* **File:** `cqrs-scenario-a-traffic-split.js`
* **Distribution:**
  * **80% Query Side (`DiagramQueryService`):**
    * 40% `GET /api/v1/diagrams?page=0&size=20` (List Projections)
    * 24% `GET /api/v1/diagrams?title=Architecture&page=0&size=10` (Search + GIN Index)
    * 16% `GET /api/v1/diagrams/{id}` (Detail)
  * **20% Command Side (`DiagramCommandService`):**
    * 10% `POST /api/v1/diagrams` (Create diagram)
    * 10% `PUT /api/v1/diagrams/{id}` (Update + Optimistic Lock + JSON payload)
* **Target:** Measure combined system throughput (RPS) and p95/p99 latency.

### Scenario B1: Query Saturation & JVM Heap Footprint (100% Query)
* **File:** `cqrs-scenario-b1-query-saturation.js`
* **Load:** 100% `GET /api/v1/diagrams?page=0&size=20` across 10 $\to$ 50 $\to$ 100 VUs.
* **Target:** Prove projection retrieval keeps JVM memory footprint low ($< 300\text{ MB}$) and $p95 < 30\text{ ms}$.

### Scenario B2: Read-Write Contention & Latency Isolation
* **File:** `cqrs-scenario-b2-contention.js`
* **Load:** Dual-scenario parallel execution:
  * 80 VUs continuous reads (`GET /diagrams`, `GET /diagrams/{id}`).
  * 20 VUs continuous heavy writes (`PUT /diagrams/{id}` with ~60KB JSON payload and version coordination).
* **Target:** Prove **Latency Isolation** — Query $p95$ stays stable $< 40\text{ ms}$ even while Command side handles heavy mutations.

---

## 4. How to Run the Benchmark

### Prerequisites
* Docker & Docker Compose
* Bash environment

### Quick Verification (Smoke Test ~30 seconds)
To rapidly verify that all scenarios run without error:
```bash
./benchmarks/cqrs/scripts/run-cqrs-benchmark-suite.sh --after --quick
```

Or run a single scenario in quick mode:
```bash
./benchmarks/cqrs/scripts/run-cqrs-benchmark-suite.sh --after --quick --scenario A
./benchmarks/cqrs/scripts/run-cqrs-benchmark-suite.sh --after --quick --scenario B1
./benchmarks/cqrs/scripts/run-cqrs-benchmark-suite.sh --after --quick --scenario B2
```

### Full Benchmark (Standard 10 $\to$ 50 $\to$ 100 VUs, 5 minutes per scenario)
1. **Run Pre-CQRS Baseline (`--before`, commit `f5adca2`):**
   ```bash
   ./benchmarks/cqrs/scripts/run-cqrs-benchmark-suite.sh --before --full
   ```
2. **Run Post-CQRS Evaluation (`--after`, branch `feat/cqrs`):**
   ```bash
   ./benchmarks/cqrs/scripts/run-cqrs-benchmark-suite.sh --after --full
   ```
3. **Inspect Results:**
   Check generated JSON summaries and telemetry statistics in `benchmarks/cqrs/results/`.
