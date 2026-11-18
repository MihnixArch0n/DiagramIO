"""DiagramIO Phase 2 / Member 2: WBS Locust TaskSets.

Usage (run from the DiagramIO repository):

  DIAGRAMIO_FIXTURES=../diagramio-benchmark-windows/fixtures.json \
    python -m locust -f tests/locust/locustfile.py CacheReadUser \
    --headless --host http://127.0.0.1:8081 --users 10 --spawn-rate 10 \
    --run-time 90s --reset-stats --only-summary

For the 20-export + 50-reader workload, use the companion
step17_run_bulkhead.py script; it creates and deletes its own test diagram
and injects bearer credentials into the Locust process only.

No writes are performed against the 20 read benchmark fixtures. Never commit
fixtures.json or export credentials.
"""

import json
import os
import random
import time
import uuid
from pathlib import Path

from gevent import sleep
from gevent.event import Event
from locust import HttpUser, TaskSet, between, constant, task


# This file is deliberately usable with BOTH fixture schemas:
# Windows: {"diagrams": [{"id": "...", "token": "..."}]}
# Kaggle:  [{"id": "...", "shareToken": "..."}]
def _load_diagrams():
    filename = os.getenv("DIAGRAMIO_FIXTURES", "").strip()
    if not filename:
        raise RuntimeError("Set DIAGRAMIO_FIXTURES to the existing private fixtures.json")
    path = Path(filename).expanduser()
    if not path.is_file():
        raise RuntimeError(f"Fixture file is missing: {path}")
    content = json.loads(path.read_text(encoding="utf-8-sig"))
    items = content.get("diagrams") if isinstance(content, dict) else content
    if not isinstance(items, list) or not items:
        raise RuntimeError("Fixture file must contain a nonempty list of diagrams")
    diagrams = []
    for item in items:
        if not isinstance(item, dict):
            raise RuntimeError("Invalid fixture: diagram must be an object")
        diagram_id = item.get("id")
        token = item.get("token") or item.get("shareToken")
        if not isinstance(diagram_id, str) or not isinstance(token, str) or not token:
            raise RuntimeError("Invalid fixture: id and share token are required")
        uuid.UUID(diagram_id)  # validate locally; never print the token
        diagrams.append({"id": diagram_id, "token": token})
    return diagrams


DIAGRAMS = _load_diagrams()


class CacheReadTaskSet(TaskSet):
    """Giai đoạn 1 WBS: read shared diagrams, no mutations."""

    @task
    def read_share(self):
        item = random.choice(DIAGRAMS)
        with self.client.get(
            f"/api/v1/shares/{item['token']}",
            name="GET /api/v1/shares/[token]",
            catch_response=True,
            timeout=20,
        ) as response:
            if response.status_code != 200:
                response.failure(f"Share read HTTP {response.status_code}; expected 200")
                return
            try:
                body = response.json()
                data = body.get("data") if isinstance(body, dict) else None
                if (
                    not isinstance(body, dict)
                    or str(body.get("id")) != item["id"]
                    or not isinstance(data, dict)
                    or not isinstance(data.get("nodes"), list)
                ):
                    response.failure("Unexpected diagram schema/id in shared read")
                else:
                    response.success()
            except (ValueError, TypeError):
                response.failure("Share read returned invalid JSON")


class CacheReadUser(HttpUser):
    """Only CacheReadTaskSet; select by name for 10/50/100 read-only runs."""

    tasks = [CacheReadTaskSet]
    wait_time = between(0.05, 0.15)


# Export-specific users run once each. A barrier ensures that 20 exports and
# 50 readers start together, rather than 20 quick JSON exports finishing before
# the first reader is spawned. These globals are intentionally process-local;
# run the mixed test with ONE Locust worker/process.
_EXPECTED_EXPORTERS = 20
_EXPECTED_READERS = 50
_ready_exporters = 0
_ready_readers = 0
_burst_gate = Event()


def _join_mixed_load(role):
    global _ready_exporters, _ready_readers
    # gevent greenlets: no context switch occurs between these increments and
    # the comparison, so these operations are atomic within this worker.
    if role == "export":
        _ready_exporters += 1
    else:
        _ready_readers += 1
    if _ready_exporters >= _EXPECTED_EXPORTERS and _ready_readers >= _EXPECTED_READERS:
        _burst_gate.set()
    if not _burst_gate.wait(timeout=25):
        raise RuntimeError(
            "Mixed-load startup barrier timed out; use exactly 70 users "
            "(20 exporters + 50 readers), one Locust process, spawn-rate 70"
        )


class ExportAsyncBulkheadTaskSet(TaskSet):
    """Giai đoạn 1 WBS: one queued export per virtual exporter, then poll/download."""

    def on_start(self):
        self.finished = False
        _join_mixed_load("export")

    @task
    def export_once(self):
        if self.finished:
            sleep(0.5)
            return
        self.finished = True  # never create additional jobs in this run
        diagram_id = os.getenv("DIAGRAMIO_EXPORT_DIAGRAM_ID", "")
        bearer = os.getenv("DIAGRAMIO_EXPORT_BEARER_TOKEN", "")
        if not diagram_id or not bearer:
            raise RuntimeError("Export fixture/token missing; use step17_run_bulkhead.py")
        headers = {"Authorization": "Bearer " + bearer}
        job_id = None
        with self.client.post(
            f"/api/v1/diagrams/{diagram_id}/export",
            headers=headers,
            name="POST /api/v1/diagrams/[id]/export",
            catch_response=True,
            timeout=20,
        ) as response:
            if response.status_code != 202:
                response.failure(f"Export submit HTTP {response.status_code}; expected 202")
                return
            try:
                body = response.json()
                job_id = str(uuid.UUID(str(body["jobId"])))
                response.success()
            except (KeyError, ValueError, TypeError):
                response.failure("202 response missing valid jobId")
                return

        # Poll only for accepted jobs; a failed worker is a real test failure.
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            with self.client.get(
                f"/api/v1/jobs/{job_id}",
                headers=headers,
                name="GET /api/v1/jobs/[jobId]",
                catch_response=True,
                timeout=15,
            ) as response:
                if response.status_code != 200:
                    response.failure(f"Poll HTTP {response.status_code}; expected 200")
                    return
                try:
                    status = response.json().get("status")
                except (TypeError, ValueError):
                    response.failure("Poll returned invalid JSON")
                    return
                if status == "FAILED":
                    response.failure("Export worker returned FAILED")
                    return
                if status not in ("QUEUED", "RUNNING", "SUCCEEDED"):
                    response.failure("Unexpected export job status")
                    return
                response.success()
                if status == "SUCCEEDED":
                    break
            sleep(0.15)
        else:
            # A synthetic failure is recorded in the Locust exception log.
            raise RuntimeError("Export job did not complete in 30 seconds")

        with self.client.get(
            f"/api/v1/jobs/{job_id}/download",
            headers=headers,
            name="GET /api/v1/jobs/[jobId]/download",
            catch_response=True,
            timeout=20,
        ) as response:
            if response.status_code != 200:
                response.failure(f"Download HTTP {response.status_code}; expected 200")
                return
            try:
                exported = response.json()
                data = exported.get("data") if isinstance(exported, dict) else None
                if (
                    str(exported.get("diagramId")) != diagram_id
                    or not isinstance(data, dict)
                    or not isinstance(data.get("nodes"), list)
                ):
                    response.failure("Exported JSON has incorrect diagramId/schema")
                else:
                    response.success()
            except (ValueError, TypeError, AttributeError):
                response.failure("Download is not valid JSON")


class BulkheadReadTaskSet(CacheReadTaskSet):
    """Reuses the exact shared-read validation, joins mixed-load barrier first."""

    def on_start(self):
        _join_mixed_load("read")


class ExportSubmitUser(HttpUser):
    """Exactly 20 virtual exporters when run with fixed_count in 70-user mode."""

    fixed_count = _EXPECTED_EXPORTERS
    tasks = [ExportAsyncBulkheadTaskSet]
    wait_time = constant(0)


class BulkheadReadUser(HttpUser):
    """Exactly 50 virtual readers running during the export burst."""

    fixed_count = _EXPECTED_READERS
    tasks = [BulkheadReadTaskSet]
    wait_time = between(0.05, 0.15)
