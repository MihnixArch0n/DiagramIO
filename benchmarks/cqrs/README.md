# In-Process CQRS Benchmark Suite

k6 load testing suite and Telegraf telemetry configuration to evaluate **In-Process CQRS** (`DiagramCommandService` & `DiagramQueryService`, ADR-0002) against the monolithic `DiagramService` baseline (`595c5b2`).

## Directory Structure

```text
benchmarks/cqrs/
├── README.md
├── scripts/
│   ├── seed-data.js                         # Seeds accounts & 150 diagrams (~50KB-1MB)
│   ├── cqrs-scenario-a-traffic-split.js     # Scenario A: 80% Read / 20% Write
│   ├── cqrs-scenario-b1-query-saturation.js # Scenario B1: 100% Query List
│   ├── cqrs-scenario-b2-contention.js       # Scenario B2: 80 Read VUs + 20 Write VUs
│   └── run-cqrs-benchmark-suite.sh          # Automated suite runner
├── monitoring/                              # Telegraf config (5s intervals)
└── results/                                 # k6 JSON summaries & telemetry
```

## Scenarios

- **Scenario A (`cqrs-scenario-a-traffic-split.js`)**: 80% Queries (`GET /diagrams`, `GET /diagrams?title=...`, `GET /diagrams/{id}`) / 20% Commands (`POST /diagrams`, `PUT /diagrams/{id}`), ramping 10 -> 50 -> 100 VUs (5m).
- **Scenario B1 (`cqrs-scenario-b1-query-saturation.js`)**: 100% `GET /api/v1/diagrams?page=0&size=20`, ramping 10 -> 50 -> 100 VUs (5m).
- **Scenario B2 (`cqrs-scenario-b2-contention.js`)**: 80 Read VUs concurrent with 20 heavy Write VUs (~60KB `PUT /diagrams/{id}`) for 4m.

## How to Run

Prerequisites: Docker, Docker Compose, Bash.

**Quick smoke test (~30s):**
```bash
./benchmarks/cqrs/scripts/run-cqrs-benchmark-suite.sh --after --quick
# Or a single scenario: --scenario A | B1 | B2
```

**Full benchmark (10 -> 50 -> 100 VUs):**
```bash
# 1. Pre-CQRS baseline (commit 595c5b2)
./benchmarks/cqrs/scripts/run-cqrs-benchmark-suite.sh --before --full

# 2. Post-CQRS evaluation (branch feat/cqrs)
./benchmarks/cqrs/scripts/run-cqrs-benchmark-suite.sh --after --full
```

Results and analysis: [docs/phase2/cqrs-benchmark-report.md](../../docs/phase2/cqrs-benchmark-report.md).
Raw benchmark results & parse scripts (GitHub Gist): [DiagramIO Phase 2 CQRS Benchmark Raw Results & Scripts](https://gist.github.com/MihnixArch0n/ca4adaff80b8c8e49a17b8add9e069b1).
