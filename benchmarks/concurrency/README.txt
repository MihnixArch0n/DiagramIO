DIAGRAMIO BENCHMARK BEFORE (Windows / Git Bash)
================================================
Purpose: run read-heavy baseline against an existing DiagramIO backend.
Checked API contract: MihnixArch0n/DiagramIO commit a212ed6.

PREREQUISITES:
- Docker containers healthy at http://localhost:8081/api/v1/health
- Python 3.13 + Locust installed in ../diagramio-locust-venv
- Run these commands in Git Bash from this extracted directory OUTSIDE repo.

1. Confirm health:
   curl -i http://127.0.0.1:8081/api/v1/health

2. Seed 20 private ERD diagrams, each ~128KiB, with share view tokens:
   ../diagramio-locust-venv/Scripts/python.exe seed.py --base-url http://127.0.0.1:8081 --count 20 --payload-kib 128

   Creates fixtures.json containing valid share tokens. Do NOT upload it or commit it.
   Repeat seeding with --replace ONLY if deliberately creating more data.

3. Smoke test first:
   mkdir -p results
   ../diagramio-locust-venv/Scripts/python.exe -m locust -f locustfile.py --headless --host http://127.0.0.1:8081 --users 1 --spawn-rate 1 --run-time 15s --only-summary --csv results/smoke --html results/smoke.html
   Confirm 0 failures. If there are errors, stop and fix those before full tests.

4. Baseline at 10, 50, 100 users; repeat same parameters for After:
   ../diagramio-locust-venv/Scripts/python.exe -m locust -f locustfile.py --headless --host http://127.0.0.1:8081 --users 10 --spawn-rate 10 --run-time 90s --reset-stats --only-summary --csv results/before_10users --html results/before_10users.html
   ../diagramio-locust-venv/Scripts/python.exe -m locust -f locustfile.py --headless --host http://127.0.0.1:8081 --users 50 --spawn-rate 10 --run-time 90s --reset-stats --only-summary --csv results/before_50users --html results/before_50users.html
   ../diagramio-locust-venv/Scripts/python.exe -m locust -f locustfile.py --headless --host http://127.0.0.1:8081 --users 100 --spawn-rate 10 --run-time 90s --reset-stats --only-summary --csv results/before_100users --html results/before_100users.html

5. Summarize:
   ../diagramio-locust-venv/Scripts/python.exe summarize.py

Run in a second terminal during each load phase:
   docker stats diagramio_app diagramio_db --no-stream
Record CPU, RAM. Both client and server share this host, so throughput includes
client-side contention. Avoid other heavy tasks, use same hardware Before/After.

Security: never publish fixtures.json, private share tokens, JWTs, .env, DB dumps.
Dataset is synthetic, isolated by title prefix BENCH_READ_.
No repository Java code or docker compose files are modified by these scripts.
