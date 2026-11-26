# ADR-0002: In-Process CQRS for Diagram Service

**Date**: 2026-11-21  
**Status**: accepted  
**Deciders**: Member 1 (Data & Storage Architecture), DiagramIO Team  

## Context

In DiagramIO Phase 1, `DiagramService` acted as a monolithic domain service ("God Class") handling both mutation commands (create, update with optimistic locking, delete, claim-check persistence coordination) and query operations (fetching diagram details, listing diagram summaries with pagination and search filtering, counting diagrams).

This monolithic design created several architectural and quality attribute challenges:
1. **Separation of Concerns & Maintainability**: Mutating logic requiring aggregate invariant enforcement, row-level locks, and CAS storage cleanup was coupled directly with read-only query operations that only need lightweight metadata.
2. **Read/Write Performance Asymmetry**: Read operations (diagram summaries and public feeds) require high throughput and low latency, utilizing lightweight database projections without hydrating the full 2MB `data` payload or opening transactional locks. Conversely, write operations require strict optimistic locking (`@Version`), authorization checks (`VIEW`/`EDIT`/`MANAGE`), and payload validation.
3. **Caching & Scaling Friction**: In Phase 2, implementing read caching (such as Cache-Aside with Redis or in-memory caches) on a monolithic service is fragile because every write operation risks inadvertently invalidating or bypassing cache boundaries without clear command/query delineations.

We need an architectural pattern to segregate read and write responsibilities while maintaining Clean Architecture inward dependencies and keeping the application in-process without unnecessary infrastructure complexity.

## Decision

We adopt the **In-Process CQRS (Command Query Responsibility Segregation)** pattern for the diagram domain:

1. **Domain Command Service (`DiagramCommandService`)**:
   - Resides in `com.diagramio.domain.diagram`. Pure Java class with zero framework annotations or imports.
   - Handles mutating business use cases: `createDiagram`, `updateDiagram`, and `deleteDiagram`.
   - Enforces aggregate root invariants, caller authorization (`DiagramAccess.requireMetadata` with `Permission.EDIT` / `MANAGE`), optimistic lock validation (`checkVersion`), and triggers cascading payload cleanup on deletion.
2. **Domain Query Service (`DiagramQueryService`)**:
   - Resides in `com.diagramio.domain.diagram`. Pure Java class with zero framework dependencies.
   - Handles read-only use cases: `getDiagramById`, `getDiagramSummariesByOwner`, `countDiagramsByOwner`, `getPublicDiagramSummaries`, and `countPublicDiagrams`.
   - Optimized for high throughput by querying lightweight `DiagramSummary` projections without loading bulk JSON blobs into memory. Enforces read permissions (`Permission.VIEW`).
   - Serves as the designated integration seam for future query caching (e.g., Cache-Aside).
3. **Application & Wiring Configuration (`DiagramConfig`)**:
   - Wires both `DiagramCommandService` and `DiagramQueryService` as Spring beans in `com.diagramio.config`.
4. **API Routing (`DiagramController`)**:
   - Injects both `DiagramCommandService` and `DiagramQueryService`.
   - Routes HTTP mutations (`POST`, `PUT`, `DELETE /api/v1/diagrams`) exclusively to `DiagramCommandService`.
   - Routes HTTP queries (`GET /api/v1/diagrams`, `GET /api/v1/diagrams/{id}`, `GET /api/v1/diagrams/public`) exclusively to `DiagramQueryService`.
   - Maintains 100% backward compatibility with `docs/openapi.yaml`.

## Alternatives Considered

### Alternative 1: Retain Monolithic `DiagramService`
- **Pros**: Fewer classes and interfaces to maintain; no controller refactoring needed.
- **Cons**: Perpetuates the "God Class" anti-pattern; mixes write locking and read projections; complicates cache-aside implementation in Phase 2.
- **Why not**: Directly contradicts Phase 2 maintainability and separation of concerns objectives.

### Alternative 2: Distributed CQRS with Event Sourcing & Separate Read Database
- **Pros**: Complete physical decoupling of read and write storage engines; infinite independent scaling of read replicas.
- **Cons**: Requires complex distributed messaging (Kafka/RabbitMQ), eventual consistency handling, event serialization schemas, and high operational overhead.
- **Why not**: Disproportionate complexity for DiagramIO's current scale. In-Process CQRS provides clean architectural boundaries and read projection optimizations within the existing PostgreSQL relational database.

## Consequences

### Positive
- **High Cohesion & Clear Responsibilities**: Command service focuses exclusively on business invariants, validation, and optimistic locking; query service focuses exclusively on projection retrieval, pagination limits, and search filtering.
- **Independent Optimization & Caching**: Query paths bypass 2MB blob hydration entirely and can be easily wrapped with cache decorators without affecting write flows.
- **Clean Architecture Compliance**: Domain services remain pure Java. Verified 100% green by ArchUnit `ArchitectureTest`.
- **Zero API Breakage**: Fully preserves existing REST contracts (`docs/openapi.yaml`) and HTTP response codes (200, 201, 400, 401, 403, 404, 409).

### Negative
- **Multiple Service Injections**: API controllers must now inject two services (`DiagramCommandService` and `DiagramQueryService`) instead of one.
- **Slightly Increased Class Count**: Two domain service classes and corresponding test classes replace the single `DiagramService`.

### Risks & Mitigations
- **Authorization Drift**: Risk of disparate permission logic between command and query paths.
  - *Mitigation*: Both services share the unified `DiagramAccess` domain helper (`require` and `requireMetadata`), guaranteeing identical permission rules across commands and queries.

## Verification & Benchmark Evidence

The In-Process CQRS architecture was evaluated against the monolithic baseline (`595c5b2`) on GitHub Codespaces (4 vCPU, 16 GB RAM) across 3 full k6 load scenarios (10 $\to$ 50 $\to$ 100 VUs) with continuous 5-second Telegraf telemetry:

* **Separation of Concerns with Zero Practical Penalty:**
  - **Scenario A (80/20 Traffic Split):** Achieved 497.49 req/s (+0.77% vs 493.67 req/s baseline) with $p50$ latency of 29.21 ms and $p95$ of 146.71 ms ($< 300\text{ ms}$ threshold).
  - **Scenario B1 (100% Query Saturation):** Sustained 903.96 req/s (+0.73% vs 897.45 req/s baseline) with identical median latency (32.94 ms vs 32.96 ms) and 0 errors across 271,266 requests.
  - **Scenario B2 (Read/Write Contention):** Sustained 927.31 req/s (+1.70% vs 911.80 req/s baseline) under continuous heavy 60KB mutation hammering with 80 Read VUs + 20 Write VUs.
* **Lean Resource Footprint:**
  - JVM Old Gen Heap maintained at an average of **56.20 MB** (peak 60.53 MB) vs **53.18 MB** (peak 57.24 MB) baseline, with sub-20ms GC pauses (avg 11.01 ms vs 11.71 ms baseline).
  - PostgreSQL DB memory reduced by 15.3% (78.72 MB avg vs 92.89 MB avg).
  - 0 rollbacks, 0 deadlocks across 1.24+ million transactions.

Detailed report: [docs/phase2/cqrs-benchmark-report.md](../phase2/cqrs-benchmark-report.md).
k6 benchmark scripts & telemetry configs: [benchmarks/cqrs/](../../benchmarks/cqrs/).
Raw benchmark results & parse scripts (GitHub Gist): [DiagramIO Phase 2 CQRS Benchmark Raw Results & Scripts](https://gist.github.com/MihnixArch0n/ca4adaff80b8c8e49a17b8add9e069b1).

