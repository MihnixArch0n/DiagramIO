"""Create a reproducible, isolated read benchmark dataset via DiagramIO public API.
Requires: requests (already bundled with Locust installation).
No Java source or database tables are altered directly.
"""
import argparse
import json
import os
from pathlib import Path
import random
import secrets
import string
import sys

import requests

ROOT = Path(__file__).resolve().parent
FIXTURE_PATH = ROOT / 'fixtures.json'


def json_call(session, method, url, expected, **kwargs):
    response = session.request(method, url, timeout=35, **kwargs)
    if response.status_code not in expected:
        print(f"ERROR {method} {url}: HTTP {response.status_code}", file=sys.stderr)
        print(response.text[:700], file=sys.stderr)
        raise RuntimeError(f"Unexpected HTTP status: {response.status_code}")
    try:
        return response.json()
    except ValueError:
        raise RuntimeError(f"Invalid JSON returned by {method} {url}") from None


def diagram_payload(size_kib, diagram_index):
    """Return a deterministic JSON value ~size_kib KiB, within DiagramData's 2MiB limit."""
    rng = random.Random(20261117 + diagram_index)
    node_count = 64
    approx_chars = max(100, (size_kib * 1024 // node_count) - 100)
    alphabet = string.ascii_letters + string.digits
    nodes = [
        {"id": f"node-{j:03d}", "label": "".join(rng.choices(alphabet, k=approx_chars))}
        for j in range(node_count)
    ]
    payload = {"nodes": nodes, "edges": []}
    nbytes = len(json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode('utf-8'))
    if nbytes > 2 * 1024 * 1024:
        raise ValueError(f"Payload exceeds 2 MiB: {nbytes} bytes")
    return payload, nbytes


def main():
    p = argparse.ArgumentParser(description="Seed DiagramIO benchmark diagrams")
    p.add_argument('--base-url', default='http://127.0.0.1:8081')
    p.add_argument('--count', type=int, default=20)
    p.add_argument('--payload-kib', type=int, default=128)
    p.add_argument('--replace', action='store_true', help='Generate new fixtures, replacing local fixtures.json (does not delete previous DB objects)')
    args = p.parse_args()
    if not (1 <= args.count <= 200):
        p.error('--count must be between 1 and 200')
    if not (1 <= args.payload_kib <= 1900):
        p.error('--payload-kib must be between 1 and 1900')
    if FIXTURE_PATH.exists() and not args.replace:
        print('fixtures.json already exists; will NOT seed again.')
        print('To create a new set intentionally, pass --replace (old DB objects remain).')
        return

    base = args.base_url.rstrip('/')
    print('Health check:', base)
    health = json_call(requests.Session(), 'GET', f'{base}/api/v1/health', [200])
    print('Health:', health)

    suffix = secrets.token_hex(6)
    username = 'bench_' + suffix
    email = f'{username}@example.com'
    password = secrets.token_urlsafe(24)
    session = requests.Session()
    session.headers.update({'Accept': 'application/json'})
    json_call(session, 'POST', f'{base}/api/v1/auth/register', [201], json={
        'username': username, 'email': email, 'password': password,
    })
    auth = json_call(session, 'POST', f'{base}/api/v1/auth/login', [200], json={
        'email': email, 'password': password,
    })
    session.headers['Authorization'] = 'Bearer ' + auth['accessToken']
    print(f'Created test owner: {username}')

    fixtures = []
    for i in range(args.count):
        title = f'BENCH_READ_{suffix}_{i:02d}'
        created = json_call(session, 'POST', f'{base}/api/v1/diagrams', [201], json={
            'title': title, 'diagramType': 'ERD',
        })
        diagram_id = created['id']
        payload, size = diagram_payload(args.payload_kib, i)
        updated = json_call(session, 'PUT', f'{base}/api/v1/diagrams/{diagram_id}', [200], json={
            'title': title, 'diagramType': 'ERD', 'version': created['version'],
            'data': payload, 'isPublic': False,
        })
        share = json_call(session, 'POST', f'{base}/api/v1/diagrams/{diagram_id}/shares', [201], json={
            'role': 'view',
        })
        token = share['shareToken']
        fixtures.append({'id': diagram_id, 'token': token, 'version': updated['version']})
        print(f'Created {i + 1}/{args.count}: {diagram_id}, payload={size / 1024:.1f} KiB')

    # Validate the actual read flow, without user authentication.
    item = fixtures[0]
    public = json_call(requests.Session(), 'GET', f"{base}/api/v1/shares/{item['token']}", [200])
    via_header = json_call(requests.Session(), 'GET', f"{base}/api/v1/diagrams/{item['id']}", [200],
                           headers={'X-Share-Token': item['token']})
    assert public.get('id') == item['id'], 'Shared read ID mismatch'
    assert via_header.get('id') == item['id'], 'Header read ID mismatch'
    assert isinstance(public.get('data'), dict) and isinstance(public['data'].get('nodes'), list), 'Invalid data'

    content = {'base_url': base, 'dataset': 'read-20-hotkeys', 'payload_kib_target': args.payload_kib,
               'count': len(fixtures), 'diagrams': fixtures}
    temp = FIXTURE_PATH.with_suffix('.json.tmp')
    temp.write_text(json.dumps(content, indent=2), encoding='utf-8')
    os.replace(temp, FIXTURE_PATH)
    print('\nPASS: Shared read and X-Share-Token read returned the correct diagram.')
    print('Fixture written:', FIXTURE_PATH)
    print('WARNING: fixtures.json contains valid share tokens; keep private and do not commit it.')


if __name__ == '__main__':
    main()
