# Step 12 – DiagramIO Benchmark Before/After

## Test protocol

- Environment: same Windows/Docker host; read-only Locust load against `http://127.0.0.1:8081`.
- Dataset: same 20 pre-seeded private ERD diagrams; **no re-seeding**.
- Workload: `locustfile.py`; 80% `GET /api/v1/shares/[token]`, 20% `GET /api/v1/diagrams/[id]` with `X-Share-Token`.
- Locust: 10 / 50 / 100 users, spawn rate 10 users/s, run time 90s, `--reset-stats --only-summary`.
- Both CSV summaries are retained under `docs/concurrency/evidence/`.

## Results

| Users | Before RPS | After RPS | RPS increase | Before P95 | After P95 | P95 reduction | Before P99 | After P99 | P99 reduction | Before errors | After errors |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 87.33 | 90.13 | +3.22% | 16.00 ms | 10.00 ms | 37.50% | 25.00 ms | 13.00 ms | 48.00% | 0/7728 | 0/8068 |
| 50 | 404.61 | 457.53 | +13.08% | 29.00 ms | 13.00 ms | 55.17% | 45.00 ms | 17.00 ms | 62.22% | 0/34323 | 0/39014 |
| 100 | 474.12 | 752.40 | +58.69% | 93.00 ms | 33.00 ms | 64.52% | 120.00 ms | 48.00 ms | 60.00% | 0/37869 | 0/60331 |

## Interpretation

- At 100 users, read throughput is higher and P95/P99 latencies are lower in the measured After run.
- Both Before and After runs show zero recorded request failures at all three loads.
- The read-only benchmark evaluates read-path performance, not async export throughput.
- Cache-Aside/Caffeine is a plausible contributor; however, this Before/After comparison alone cannot attribute the full change exclusively to caching.

## Validity and limitations

- These are single recorded runs per load level. Rerun multiple times to quantify run-to-run variability.
- Client and server ran on the same host: CPU scheduling, Docker resource contention, thermal effects, and background activity can affect RPS/latency.
- Cache warm/cold state was not controlled by these two summary CSVs. Do not claim identical starting cache conditions.
- Record CPU/RAM separately if needed; neither summary CSV contains these measurements.
- Keep `fixtures.json` private; it contains share tokens. Never commit credentials.
