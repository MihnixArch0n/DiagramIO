"""Combine three Locust aggregate stats rows into results/before_summary.csv."""
import csv
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / 'results'
SUMMARY = RESULTS / 'before_summary.csv'
rows = []
for users in (10, 50, 100):
    input_path = RESULTS / f'before_{users}users_stats.csv'
    if not input_path.exists():
        raise SystemExit(f'Missing {input_path}; benchmark {users} users first')
    with input_path.open(newline='', encoding='utf-8-sig') as f:
        stats = list(csv.DictReader(f))
    agg = next((r for r in stats if r.get('Name') == 'Aggregated'), None)
    if agg is None:
        raise SystemExit(f'Aggregated row missing in {input_path}')
    request_count = int(agg['Request Count'])
    failure_count = int(agg['Failure Count'])
    row = {
        'users': users, 'requests': request_count,
        'failures': failure_count,
        'error_pct': round(100 * failure_count / request_count, 3) if request_count else 0,
        'rps': agg['Requests/s'],
        'p95_ms': agg['95%'],
        'p99_ms': agg['99%'],
    }
    rows.append(row)
RESULTS.mkdir(parents=True, exist_ok=True)
with SUMMARY.open('w', newline='', encoding='utf-8') as f:
    writer = csv.DictWriter(f, fieldnames=rows[0].keys())
    writer.writeheader()
    writer.writerows(rows)
print('Summary:', SUMMARY)
for row in rows:
    print(row)
