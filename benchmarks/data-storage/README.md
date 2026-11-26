# Data & Storage Benchmark Suite

This directory contains the load testing suite, monitoring configuration, and benchmark results for the **Phase 2 Data & Storage Optimizations**.

## 1. Overview of Improvements
* **PostgreSQL Index Tuning:** Added `pg_trgm` GIN Trigram index on `diagrams(title)` to accelerate substring search from full sequential scan to index scan (< 2ms), plus composite indexes on foreign keys and ordering columns.
* **JPA Projections:** Introduced summary projection DTOs for diagram and version listings, completely bypassing the heavy 2MB JSON `data` column to eliminate JVM Heap memory bloat and GC pauses.
* **Claim-Check Pattern:** Offloaded 2MB JSON diagram payloads from PostgreSQL TOAST tables to a dedicated filesystem volume (`BlobStoragePort` & `FileSystemBlobStorageAdapter`), storing only compact `storageKey` references in the database.

---

## 2. Directory Structure

```text
benchmarks/data-storage/
├── README.md               # This execution guide
├── scripts/                # k6 scenarios, runner, seeding, DB size query, telemetry parser
├── monitoring/             # Telegraf container configuration (1s host & container metrics)
└── results/                # Local test output directory (git-ignored; raw results hosted on Gist)
```

---

## 3. How to Run the Benchmark

### Prerequisites
* Docker & Docker Compose
* Bash environment

### Running Tests

1. **Run Before Benchmark (Pre-improvement state):**
   ```bash
   ./benchmarks/data-storage/scripts/run-benchmark-suite.sh --before
   ```

2. **Run After Benchmark (Post-improvement state):**
   ```bash
   ./benchmarks/data-storage/scripts/run-benchmark-suite.sh --after
   ```

The script automatically cleans containers and volumes, builds the image, starts Telegraf monitoring, seeds 50 realistic diagrams (50KB to 1.75MB), executes the 5-minute k6 test (10 $\to$ 50 $\to$ 100 VUs), and saves raw metrics to `results/`.

---

## 4. Key Results Summary

Tested on 4 vCPU, 16 GB RAM (GitHub Codespaces standardLinux32gb):

| Metric | Before | After Improvements | Delta |
|---|---|---|---|
| **Throughput (RPS)** | 77.16 req/s | **843.52 req/s** | **+993.2% (10.9x)** |
| **Average Latency** | 608.76 ms | **39.09 ms** | **-93.6% (15.6x faster)** |
| **Overall p95 Latency** | 2,040.00 ms | **119.72 ms** | **-94.1% (17.0x faster)** |
| **Task 1 (GET list) p95** | 2,300.00 ms | **118.00 ms** | **-94.9% (19.5x faster)** |
| **Task 2 (Search title) p95**| 2,360.00 ms | **117.11 ms** | **-95.0% (20.2x faster)** |
| **Task 3 (GET detail) p95**| 1,180.00 ms | **123.62 ms** | **-89.5% (9.5x faster)** |
| **JVM Peak RAM** | 2,289.21 MB | **848.73 MB** | **-62.9% RAM savings** |
| **PostgreSQL DB CPU Avg** | 274.57% | **23.46%** | **-91.5% CPU reduction** |
| **PostgreSQL DB Storage** | 4,064 kB | **344 kB** | **-91.5% DB footprint reduction** |

Full report and detailed analysis: [docs/phase2/data-storage-benchmark-report.md](../../docs/phase2/data-storage-benchmark-report.md).  
Raw benchmark artifacts & metrics telemetry: [GitHub Gist](https://gist.github.com/MihnixArch0n/e88f0838c5e757cdeebf7106d7ccbf4f).
