import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 10 },  // Level 1: 10 VUs
    { duration: '1m',  target: 10 },
    { duration: '30s', target: 50 },  // Level 2: 50 VUs
    { duration: '1m',  target: 50 },
    { duration: '30s', target: 100 }, // Level 3: 100 VUs
    { duration: '1m',  target: 100 },
    { duration: '30s', target: 0 },   // Ramp down
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:list}': ['p(95)<1000'],
    'http_req_duration{name:search}': ['p(95)<1000'],
    'http_req_duration{name:detail}': ['p(95)<1000'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

export function setup() {
  console.log(`Setting up benchmark test on ${BASE_URL}...`);

  // 1. Authenticate and retrieve bearer token
  let token = null;
  const loginRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    email: 'uet@gmail.com',
    password: 'uet123456',
  }), { headers: { 'Content-Type': 'application/json' } });

  if (loginRes.status === 200) {
    token = loginRes.json('accessToken');
  }

  if (!token) {
    throw new Error('Authentication failed in k6 setup()');
  }

  // 2. Fetch diagram ID pool for Task 3 (detail)
  const listRes = http.get(`${BASE_URL}/api/v1/diagrams?page=0&size=50`, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });

  const content = listRes.json('content') || [];
  const diagramIds = content.map(item => item.id);
  console.log(`Found ${diagramIds.length} diagrams for detail benchmarking.`);

  if (diagramIds.length === 0) {
    console.warn('WARNING: No diagrams found! Run seed-data.js first.');
  }

  return { token, diagramIds };
}

export default function (data) {
  const params = {
    headers: {
      Authorization: `Bearer ${data.token}`,
      'Content-Type': 'application/json',
    },
  };

  // --------------------------------------------------------------------------
  // Task 1 — Measure JPA Projection (name: list)
  // Goal: Verify list API avoids loading 2MB payload -> JVM RAM drops, fast p95
  // --------------------------------------------------------------------------
  const listRes = http.get(`${BASE_URL}/api/v1/diagrams?page=0&size=20`, {
    ...params,
    tags: { name: 'list' },
  });
  check(listRes, { 'list status is 200': (r) => r.status === 200 });

  // --------------------------------------------------------------------------
  // Task 2 — Measure PostgreSQL Trigram Index (name: search)
  // Goal: Verify substring search converts from Seq Scan to Index Scan via pg_trgm
  // --------------------------------------------------------------------------
  const searchRes = http.get(`${BASE_URL}/api/v1/diagrams?title=Architecture&page=0&size=10`, {
    ...params,
    tags: { name: 'search' },
  });
  check(searchRes, { 'search status is 200': (r) => r.status === 200 });

  // --------------------------------------------------------------------------
  // Task 3 — Measure Claim-Check Pattern (name: detail)
  // Goal: Read 1 diagram payload to benchmark TOAST table vs external blob storage
  // --------------------------------------------------------------------------
  if (data.diagramIds && data.diagramIds.length > 0) {
    const randomId = data.diagramIds[Math.floor(Math.random() * data.diagramIds.length)];
    const detailRes = http.get(`${BASE_URL}/api/v1/diagrams/${randomId}`, {
      ...params,
      tags: { name: 'detail' },
    });
    check(detailRes, { 'detail status is 200': (r) => r.status === 200 });
  }

  sleep(0.05);
}
