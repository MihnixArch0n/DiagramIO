import http from 'k6/http';
import { check, sleep } from 'k6';

const isQuick = __ENV.QUICK === 'true';

export const options = isQuick ? {
  scenarios: {
    scenario_read: {
      executor: 'constant-vus',
      vus: 8,
      duration: '25s',
      exec: 'readTraffic',
    },
    scenario_write: {
      executor: 'constant-vus',
      vus: 2,
      duration: '25s',
      exec: 'writeTraffic',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{scenario:scenario_read}': ['p(95)<50', 'p(99)<100'],
    'http_req_duration{scenario:scenario_write}': ['p(95)<1000'],
  },
} : {
  scenarios: {
    scenario_read: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 20 },
        { duration: '30s', target: 50 },
        { duration: '30s', target: 80 },
        { duration: '2m',  target: 80 },  // Sustained 80 VUs read
        { duration: '30s', target: 0 },
      ],
      exec: 'readTraffic',
    },
    scenario_write: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 5 },
        { duration: '30s', target: 12 },
        { duration: '30s', target: 20 },
        { duration: '2m',  target: 20 },  // Sustained 20 VUs heavy writes
        { duration: '30s', target: 0 },
      ],
      exec: 'writeTraffic',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // Architectural SLA: Query p95 must remain < 40ms even under heavy write hammering
    'http_req_duration{scenario:scenario_read}': ['p(95)<40', 'p(99)<80'],
    'http_req_duration{scenario:scenario_write}': ['p(95)<1000'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

// Generate realistic heavy update payload (~60KB)
function createHeavyPayload() {
  const nodes = [];
  const edges = [];
  for (let i = 0; i < 75; i++) {
    nodes.push({
      id: `node-${i}`,
      label: `Contention_Entity_${i}`,
      type: i % 2 === 0 ? "UML_CLASS" : "ERD_TABLE",
      position: { x: (i % 10) * 120, y: Math.floor(i / 10) * 80 },
      data: {
        attributes: ["id: UUID", "payload: JSONB", "status: VARCHAR(32)"],
        methods: ["processCAS()", "validateInvariants()", "commitTransaction()"]
      }
    });
    if (i > 0) {
      edges.push({
        id: `edge-${i}`,
        source: `node-${i - 1}`,
        target: `node-${i}`,
        label: "dependsOn"
      });
    }
  }
  return { nodes, edges };
}

const heavyPayload = createHeavyPayload();

export function setup() {
  console.log(`Setting up Scenario B2 (Read-Write Contention & Latency Isolation) on ${BASE_URL}...`);

  let token = null;
  const loginRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    email: 'uet@gmail.com',
    password: 'uet123456',
  }), { headers: { 'Content-Type': 'application/json' } });

  if (loginRes.status === 200) {
    token = loginRes.json('accessToken');
  } else {
    const loginRes2 = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
      email: 'benchmarker@example.com',
      password: 'Password123!',
    }), { headers: { 'Content-Type': 'application/json' } });
    if (loginRes2.status === 200) {
      token = loginRes2.json('accessToken');
    }
  }

  if (!token) {
    throw new Error('Authentication failed in setup() for Scenario B2');
  }

  // Fetch diagrams pool
  const listRes = http.get(`${BASE_URL}/api/v1/diagrams?page=0&size=100`, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });

  const content = listRes.json('content') || [];
  const diagrams = content.map(item => ({
    id: item.id,
    title: item.title,
    version: item.version,
    diagramType: item.diagramType,
  }));

  console.log(`Retrieved ${diagrams.length} diagrams for contention test.`);
  if (diagrams.length === 0) {
    console.warn('WARNING: No diagrams found! Run seed-data.js first.');
  }

  return { token, diagrams };
}

// In-VU state for writer VUs
const writerVersions = {};

// ----------------------------------------------------------------------------
// Scenario 1: READ TRAFFIC (80 VUs)
// Pure reads through DiagramQueryService
// ----------------------------------------------------------------------------
export function readTraffic(data) {
  const authHeaders = {
    Authorization: `Bearer ${data.token}`,
    'Content-Type': 'application/json',
  };

  const diagrams = data.diagrams;
  const hasDiagrams = diagrams && diagrams.length > 0;
  const roll = Math.random() * 100;

  if (roll < 70) {
    // 70% Reads: List summaries via Projection
    const res = http.get(`${BASE_URL}/api/v1/diagrams?page=0&size=20`, {
      headers: authHeaders,
      tags: { name: 'contention_read_list', type: 'query' },
    });
    check(res, { 'read list is 200': (r) => r.status === 200 });
  } else {
    // 30% Reads: Detail diagram
    if (hasDiagrams) {
      const targetDiagram = diagrams[Math.floor(Math.random() * diagrams.length)];
      const res = http.get(`${BASE_URL}/api/v1/diagrams/${targetDiagram.id}`, {
        headers: authHeaders,
        tags: { name: 'contention_read_detail', type: 'query' },
      });
      check(res, { 'read detail is 200': (r) => r.status === 200 });
    }
  }

  sleep(0.02);
}

// ----------------------------------------------------------------------------
// Scenario 2: WRITE TRAFFIC (20 VUs)
// Heavy mutations through DiagramCommandService
// ----------------------------------------------------------------------------
export function writeTraffic(data) {
  const authHeaders = {
    Authorization: `Bearer ${data.token}`,
    'Content-Type': 'application/json',
  };

  const diagrams = data.diagrams;
  if (!diagrams || diagrams.length === 0) {
    sleep(0.1);
    return;
  }

  // Assign a dedicated diagram per writer VU to measure pure throughput & write latency
  const writerIdx = (__VU > 0 ? __VU - 1 : 0);
  const targetDiagram = diagrams[writerIdx % diagrams.length];

  let currentVersion = writerVersions[__VU] !== undefined ? writerVersions[__VU] : targetDiagram.version;

  const res = http.put(`${BASE_URL}/api/v1/diagrams/${targetDiagram.id}`, JSON.stringify({
    title: `${targetDiagram.title} [Contention-VU-${__VU}]`,
    diagramType: targetDiagram.diagramType || 'UML',
    version: currentVersion,
    data: heavyPayload,
    isPublic: false,
  }), {
    headers: authHeaders,
    tags: { name: 'contention_write_update', type: 'command' },
  });

  if (res.status === 200) {
    writerVersions[__VU] = res.json('version');
  } else if (res.status === 409) {
    // Re-sync version if a conflict happens
    const syncRes = http.get(`${BASE_URL}/api/v1/diagrams/${targetDiagram.id}`, {
      headers: authHeaders,
      tags: { name: 'contention_write_sync', type: 'query' },
    });
    if (syncRes.status === 200) {
      writerVersions[__VU] = syncRes.json('version');
    }
  }

  check(res, { 'write completed or version conflict': (r) => r.status === 200 || r.status === 409 });

  sleep(0.05);
}
