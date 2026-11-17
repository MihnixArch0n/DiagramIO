"""Read-only DiagramIO Locust workload: 80% public share, 20% X-Share-Token read."""
import json
import os
from pathlib import Path
import random

from locust import HttpUser, between, task

ROOT = Path(__file__).resolve().parent
DATASET_PATH = Path(os.environ.get('DIAGRAMIO_FIXTURES', str(ROOT / 'fixtures.json')))
if not DATASET_PATH.is_file():
    raise RuntimeError(f'Missing {DATASET_PATH}. Run seed.py first.')
content = json.loads(DATASET_PATH.read_text(encoding='utf-8'))
DIAGRAMS = content['diagrams']
if not DIAGRAMS:
    raise RuntimeError('fixtures.json has no diagrams.')


class DiagramReadUser(HttpUser):
    wait_time = between(0.05, 0.15)

    @task(4)
    def read_shared(self):
        item = random.choice(DIAGRAMS)
        with self.client.get(
            f"/api/v1/shares/{item['token']}",
            name='GET /api/v1/shares/[token]',
            catch_response=True, timeout=25,
        ) as response:
            self._validate(response, item)

    @task(1)
    def read_by_id_with_share_header(self):
        item = random.choice(DIAGRAMS)
        with self.client.get(
            f"/api/v1/diagrams/{item['id']}",
            headers={'X-Share-Token': item['token']},
            name='GET /api/v1/diagrams/[id]',
            catch_response=True, timeout=25,
        ) as response:
            self._validate(response, item)

    @staticmethod
    def _validate(response, item):
        if response.status_code != 200:
            response.failure(f'HTTP {response.status_code}: {response.text[:160]}')
            return
        try:
            payload = response.json()
            if payload.get('id') != item['id']:
                response.failure('diagram id mismatch')
            elif not isinstance(payload.get('data'), dict):
                response.failure('diagram data is not an object')
            elif not isinstance(payload['data'].get('nodes'), list):
                response.failure('diagram nodes is not an array')
            else:
                response.success()
        except (ValueError, TypeError, KeyError) as exc:
            response.failure(f'Bad response JSON: {exc}')
