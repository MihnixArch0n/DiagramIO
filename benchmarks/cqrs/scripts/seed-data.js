import http from 'k6/http';
import { check } from 'k6';

export const options = {
  iterations: 1,
  vus: 1,
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';
const TARGET_DIAGRAM_COUNT = parseInt(__ENV.TARGET_COUNT || '150', 10);

// Generate valid nodes and edges payload reaching the target size in bytes
function generateDiagramPayload(targetBytes) {
  const nodes = [];
  const edges = [];

  const attrSample = [
    "id: UUID PRIMARY_KEY NOT_NULL",
    "createdAt: TIMESTAMP_WITH_TIME_ZONE",
    "updatedAt: TIMESTAMP_WITH_TIME_ZONE",
    "tenantId: VARCHAR(64) INDEXED",
    "metadataPayload: JSONB NOT_NULL",
    "version: INTEGER DEFAULT_1",
    "status: ENUM('ACTIVE', 'SUSPENDED', 'PENDING')"
  ];
  const methodSample = [
    "validateAggregateInvariants(): Boolean",
    "calculateMemoryFootprint(): Long",
    "serializeToClaimCheckStorage(): StorageKey",
    "verifyOptimisticLock(expectedVersion: Int): Void"
  ];

  const bytesPerNode = 820;
  let nodeCount = Math.max(5, Math.floor(targetBytes / bytesPerNode));

  for (let i = 0; i < nodeCount; i++) {
    nodes.push({
      id: `node-${i}`,
      label: `Entity_${i}_ArchitectureComponent_WithHeavyMetadata_${i}`,
      type: i % 2 === 0 ? "UML_CLASS" : "ERD_TABLE",
      position: { x: (i % 20) * 180, y: Math.floor(i / 20) * 120 },
      data: {
        attributes: attrSample,
        methods: methodSample,
        description: `This is a benchmark entity node index ${i} designed to simulate realistic diagram graph storage footprint.`
      }
    });

    if (i > 0) {
      edges.push({
        id: `edge-${i}`,
        source: `node-${i - 1}`,
        target: `node-${i}`,
        label: i % 3 === 0 ? "oneToMany" : "dependsOn"
      });
    }
  }

  let payload = { nodes, edges };
  let jsonStr = JSON.stringify(payload);

  const maxBytes = 2 * 1024 * 1024;
  while (jsonStr.length > maxBytes - 20000 && nodes.length > 5) {
    nodes.pop();
    if (edges.length > 0) edges.pop();
    payload = { nodes, edges };
    jsonStr = JSON.stringify(payload);
  }

  return payload;
}

export default function () {
  console.log(`Connecting to ${BASE_URL} to seed CQRS benchmark data...`);

  // 1. Authenticate
  let token = null;
  const loginRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    email: 'uet@gmail.com',
    password: 'uet123456',
  }), { headers: { 'Content-Type': 'application/json' } });

  if (loginRes.status === 200) {
    token = loginRes.json('accessToken');
  }

  if (!token) {
    const registerRes = http.post(`${BASE_URL}/api/v1/auth/register`, JSON.stringify({
      username: 'benchmarker',
      email: 'benchmarker@example.com',
      password: 'Password123!',
    }), { headers: { 'Content-Type': 'application/json' } });

    if (registerRes.status === 201) {
      token = registerRes.json('accessToken');
    } else {
      const loginRes2 = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
        email: 'benchmarker@example.com',
        password: 'Password123!',
      }), { headers: { 'Content-Type': 'application/json' } });
      token = loginRes2.json('accessToken');
    }
  }

  check(token, { 'Authentication succeeded': (t) => t !== null && t !== undefined });
  if (!token) {
    throw new Error('Failed to authenticate for data seeding!');
  }

  const authHeaders = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };

  // 2. Check existing diagram count
  const listRes = http.get(`${BASE_URL}/api/v1/diagrams?page=0&size=100`, { headers: authHeaders });
  const existingTotal = listRes.json('totalElements') || 0;
  console.log(`Existing diagrams in account: ${existingTotal}`);

  if (existingTotal >= TARGET_DIAGRAM_COUNT) {
    console.log(`Already have ${existingTotal} diagrams (>= ${TARGET_DIAGRAM_COUNT}). Skipping creation.`);
    return;
  }

  const toCreate = TARGET_DIAGRAM_COUNT - existingTotal;
  console.log(`Generating ${toCreate} diagrams (target: ${TARGET_DIAGRAM_COUNT}) with realistic payloads...`);

  for (let i = 0; i < toCreate; i++) {
    const globalIdx = existingTotal + i;
    // Size distribution: 70% light (50KB), 20% medium (300KB), 10% heavy (1MB)
    let targetBytes = 50 * 1024;
    let sizeLabel = "Light (50KB)";
    if (i % 10 < 7) {
      targetBytes = 50 * 1024;
      sizeLabel = "Light (50KB)";
    } else if (i % 10 < 9) {
      targetBytes = 300 * 1024;
      sizeLabel = "Medium (300KB)";
    } else {
      targetBytes = 1000 * 1024;
      sizeLabel = "Heavy (1MB)";
    }

    const titleKeywords = ["Architecture Diagram", "Microservice Flow", "Database ERD", "Domain Model", "Core System", "Event Broker Topology"];
    const keyword = titleKeywords[globalIdx % titleKeywords.length];
    const title = `${keyword} ${globalIdx + 1} - ${sizeLabel}`;
    const diagramType = globalIdx % 2 === 0 ? "UML" : "ERD";

    // Step A: Create diagram shell
    const createRes = http.post(`${BASE_URL}/api/v1/diagrams`, JSON.stringify({
      title: title,
      diagramType: diagramType,
    }), { headers: authHeaders });

    if (createRes.status !== 201) {
      console.error(`Failed to create diagram ${globalIdx}: ${createRes.body}`);
      continue;
    }

    const diagramId = createRes.json('id');
    const version = createRes.json('version');

    // Step B: Update with realistic JSON payload
    const payload = generateDiagramPayload(targetBytes);
    const updateRes = http.put(`${BASE_URL}/api/v1/diagrams/${diagramId}`, JSON.stringify({
      title: title,
      diagramType: diagramType,
      version: version,
      data: payload,
      isPublic: globalIdx % 5 === 0 // 20% public
    }), { headers: authHeaders });

    if (updateRes.status !== 200) {
      console.error(`Failed to update payload for diagram ${diagramId}: ${updateRes.body}`);
      continue;
    }

    // Step C: For some diagrams, add a snapshot version
    if (globalIdx % 5 === 0) {
      const updatedVersion = updateRes.json('version');
      http.post(`${BASE_URL}/api/v1/diagrams/${diagramId}/versions`, JSON.stringify({
        note: `Initial Snapshot v1 for ${title}`,
        version: updatedVersion,
      }), { headers: authHeaders });
    }

    if ((i + 1) % 15 === 0 || i === toCreate - 1) {
      console.log(`Seeded ${i + 1}/${toCreate} diagrams...`);
    }
  }

  console.log('CQRS benchmark data seeding completed successfully!');
}
