import http from 'k6/http';
import { check } from 'k6';

const isQuick = __ENV.QUICK === 'true';

export const options = isQuick ? {
  stages: [
    { duration: '5s', target: 5 },
    { duration: '15s', target: 10 },
    { duration: '5s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.005'],
    'http_req_duration{name:query_projection_list}': ['p(95)<40', 'p(99)<80'],
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
    http_req_failed: ['rate<0.005'],
    'http_req_duration{name:query_projection_list}': ['p(95)<30', 'p(99)<60'],
    http_reqs: ['rate>500'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

export function setup() {
  console.log(`Setting up Scenario B1 (100% Query Saturation) on ${BASE_URL}...`);

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
    throw new Error('Authentication failed in setup() for Scenario B1');
  }

  return { token };
}

export default function (data) {
  const authHeaders = {
    Authorization: `Bearer ${data.token}`,
    'Content-Type': 'application/json',
  };

  // 100% Query: List Projection to measure JVM memory saturation and low p95
  const res = http.get(`${BASE_URL}/api/v1/diagrams?page=0&size=20`, {
    headers: authHeaders,
    tags: { name: 'query_projection_list', type: 'query' },
  });

  check(res, {
    'projection list status is 200': (r) => r.status === 200,
    'has diagrams content': (r) => {
      const body = r.json();
      return body && Array.isArray(body.content);
    },
  });
}
