# Step 3 - Before Benchmark

## Objective

Establish the performance baseline before implementing
Cache-Aside, Anti-Stampede, Async and Bulkhead.

## Environment

- Windows + Docker Desktop
- Java 25
- PostgreSQL 18
- Locust 2.47.0
- Branch: feature/phase2-concurrency
- Baseline commit: 902b46c

## Workload

- 20 ERD diagrams
- Approximately 123.6 KiB per diagram
- 80% shared diagram reads
- 20% authorized diagram reads
- 10, 50 and 100 virtual users
- Configured runtime: 90 seconds per load level

## Results

| Users | Requests | RPS | p95 (ms) | p99 (ms) | Errors |
|---|---:|---:|---:|---:|---:|
| 10 | 7728 | 87.33 | 16 | 25 | 0% |
| 50 | 34323 | 404.61 | 29 | 45 | 0% |
| 100 | 37869 | 474.12 | 93 | 120 | 0% |

Total requests: 79920
Total failures: 0

## Observations

From 50 to 100 virtual users:

- Throughput increases by approximately 17.2%.
- p95 latency increases from 29 ms to 93 ms.
- No failed requests were recorded.

These results suggest a possible scaling bottleneck,
but the exact cause has not been identified.

## Reproducibility

Scripts and summary results are stored under
benchmarks/concurrency/.

The full Before benchmark archive is retained
separately for future Before/After comparison.

## Conclusion

Step 3 completed.

Next: Design Cache-Aside and Anti-Stampede.
