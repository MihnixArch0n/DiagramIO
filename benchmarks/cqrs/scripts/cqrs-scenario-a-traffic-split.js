import http from 'k6/http';
import { check, sleep } from 'k6';

const isQuick = __ENV.QUICK === 'true';

export const options = isQuick ? {
  stages: [
    { duration: '5s', target: 5 },
    { duration: '15s', target: 10 },
    { duration: '5s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{type:query}': ['p(95)<300'],
    'http_req_duration{type:command}': ['p(95)<1000'],
  },
} : {
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
    'http_req_duration{type:query}': ['p(95)<100'],
    'http_req_duration{type:command}': ['p(95)<800'],
    'http_req_duration{name:query_list}': ['p(95)<50'],
    'http_req_duration{name:query_search}': ['p(95)<50'],
    'http_req_duration{name:query_detail}': ['p(95)<150'],
    'http_req_duration{name:command_create}': ['p(95)<500'],
    'http_req_duration{name:command_update}': ['p(95)<800'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

// Pre-generated diagram update payload (~60KB) to avoid serialization overhead in VU loop
function createUpdatePayload() {
  const nodes = [];
  const edges = [];
  for (let i = 0; i < 75; i++) {
    nodes.push({
      id: `node-${i}`,
      label: `Class_Entity_${i}`,
      type: i % 2 === 0 ? "UML_CLASS" : "ERD_TABLE",
      position: { x: (i % 10) * 120, y: Math.floor(i / 10) * 80 },
      data: {
        attributes: ["id: UUID", "name: VARCHAR", "created: TIMESTAMP"],
        methods: ["execute()", "rollback()"]
      }
    });
    if (i > 0) {
      edges.push({
        id: `edge-${i}`,
        source: `node-${i - 1}`,
        target: `node-${i}`,
        label: "references"
      });
    }
  }
  return { nodes, edges };
}

const cachedPayload = createUpdatePayload();

export function setup() {
  console.log(`Setting up Scenario A (80% Read / 20% Write) on ${BASE_URL}...`);

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
    throw new Error('Authentication failed in setup() for Scenario A');
  }

  // Fetch pool of diagrams
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

  console.log(`Retrieved ${diagrams.length} diagrams for benchmark.`);
  if (diagrams.length === 0) {
    console.warn('WARNING: No diagrams found! Run seed-data.js first.');
  }

  return { token, diagrams };
}

// In-VU state: track current known version of the VU's dedicated diagram
const vuVersions = {};

export default function (data) {
  const authHeaders = {
    Authorization: `Bearer ${data.token}`,
    'Content-Type': 'application/json',
  };

  const diagrams = data.diagrams;
  const hasDiagrams = diagrams && diagrams.length > 0;
  // Assign dedicated diagram to this VU to avoid artificial 409 conflict collisions
  const vuIndex = (__VU > 0 ? __VU - 1 : 0);
  const myDiagram = hasDiagrams ? diagrams[vuIndex % diagrams.length] : null;

  const roll = Math.random() * 100;

  if (roll < 40) {
    // ------------------------------------------------------------------------
    // Query 1 (40%): List Projections (DiagramQueryService)
    // ------------------------------------------------------------------------
    const res = http.get(`${BASE_URL}/api/v1/diagrams?page=0&size=20`, {
      headers: authHeaders,
      tags: { name: 'query_list', type: 'query' },
    });
    check(res, { 'query_list status is 200': (r) => r.status === 200 });

  } else if (roll < 64) {
    // ------------------------------------------------------------------------
    // Query 2 (24%): Search + GIN Trigram Index (DiagramQueryService)
    // ------------------------------------------------------------------------
    const res = http.get(`${BASE_URL}/api/v1/diagrams?title=Architecture&page=0&size=10`, {
      headers: authHeaders,
      tags: { name: 'query_search', type: 'query' },
    });
    check(res, { 'query_search status is 200': (r) => r.status === 200 });

  } else if (roll < 80) {
    // ------------------------------------------------------------------------
    // Query 3 (16%): Diagram Detail (DiagramQueryService)
    // ------------------------------------------------------------------------
    if (hasDiagrams) {
      const randDiagram = diagrams[Math.floor(Math.random() * diagrams.length)];
      const res = http.get(`${BASE_URL}/api/v1/diagrams/${randDiagram.id}`, {
        headers: authHeaders,
        tags: { name: 'query_detail', type: 'query' },
      });
      check(res, { 'query_detail status is 200': (r) => r.status === 200 });
    }

  } else if (roll < 90) {
    // ------------------------------------------------------------------------
    // Command 1 (10%): Create Diagram (DiagramCommandService)
    // ------------------------------------------------------------------------
    const res = http.post(`${BASE_URL}/api/v1/diagrams`, JSON.stringify({
      title: `VU-${__VU}-Dyn-${Date.now() % 100000}`,
      diagramType: 'UML',
    }), {
      headers: authHeaders,
      tags: { name: 'command_create', type: 'command' },
    });
    check(res, { 'command_create status is 201': (r) => r.status === 201 });

  } else {
    // ------------------------------------------------------------------------
    // Command 2 (10%): Update Diagram with Optimistic Lock & Payload (DiagramCommandService)
    // ------------------------------------------------------------------------
    if (myDiagram) {
      let currentVersion = vuVersions[__VU] !== undefined ? vuVersions[__VU] : myDiagram.version;

      const res = http.put(`${BASE_URL}/api/v1/diagrams/${myDiagram.id}`, JSON.stringify({
        title: `${myDiagram.title} [Updated VU ${__VU}]`,
        diagramType: myDiagram.diagramType || 'UML',
        version: currentVersion,
        data: cachedPayload,
        isPublic: false,
      }), {
        headers: authHeaders,
        tags: { name: 'command_update', type: 'command' },
      });

      if (res.status === 200) {
        vuVersions[__VU] = res.json('version');
      } else if (res.status === 409) {
        // Sync version on conflict
        const detail = http.get(`${BASE_URL}/api/v1/diagrams/${myDiagram.id}`, {
          headers: authHeaders,
          tags: { name: 'query_sync_version', type: 'query' },
        });
        if (detail.status === 200) {
          vuVersions[__VU] = detail.json('version');
        }
      }

      check(res, { 'command_update succeeded or version checked': (r) => r.status === 200 || r.status === 409 });
    }
  }

  sleep(0.05); // Short think time between requests
}
