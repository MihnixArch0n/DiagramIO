# DiagramIo Project Guidelines

## Tech Stack

- Use Java 25 and Spring Boot 4 for the backend REST API.
- Use PostgreSQL 18 as the primary database.
- Use Spring Data JPA with Hibernate for persistence and Flyway for versioned database migrations.
- Use Spring Security for authentication and authorization. Implement short-lived JWT access tokens, refresh-token rotation/revocation, and BCrypt or Argon2 password hashing; do not store refresh tokens in plaintext.
- Use Jakarta Bean Validation (Hibernate Validator) for request validation and Jackson for JSON serialization/deserialization.
- Use JUnit 5, Mockito, and Spring Boot Test/MockMvc for automated tests. Use Testcontainers with PostgreSQL for tests that require database behavior when practical.
- Use springdoc-openapi for OpenAPI/Swagger documentation.
- Use Gradle (Kotlin DSL) for build and dependency management via `gradle/libs.versions.toml`; do not introduce Maven or `pom.xml`.
- Package and run the service with Docker and Docker Compose. Keep local infrastructure configuration reproducible and do not commit secrets.
- For Phase 2 architectural improvements, whoever implements a specific quality attribute improvement decides the appropriate technology/libraries for their module (e.g., caching, messaging, observability, export), provided it integrates cleanly through Clean Architecture ports and adapters.

## Architecture

- Phase 1 established the baseline system. Phase 2 focuses on architectural improvements driven by quality attributes (Performance, Scalability, Observability, Reliability, Security).
- Maintain the three-layer Clean Architecture: API -> Business (domain) -> Data access (infrastructure). Dependencies point inward only.

| Layer | Package | May use |
|---|---|---|
| API | `com.diagramio.api` | Spring Web: controllers, DTOs, `@RestControllerAdvice` that maps domain exceptions to HTTP status codes |
| Business | `com.diagramio.domain` | Plain Java only: domain models, services, repository interfaces, domain ports, domain exceptions |
| Data access / Infra | `com.diagramio.infrastructure` | Spring Data JPA: `@Entity`, `JpaRepository`, adapter classes implementing domain repository and port interfaces, external integrations, caching, security filters, Entity <-> domain mappers |
| Wiring | `com.diagramio.config` | Spring configuration: security, OpenAPI, `@Bean` definitions that wire domain services with infrastructure adapters |

- The business layer must NOT import any web framework, database library, or infrastructure caching/messaging tool (no Spring, `jakarta.persistence`, Hibernate, Servlet, Redis, etc.). No `@Service`, `@Transactional`, `@Entity`, or `ResponseEntity` in `domain`. Any new infrastructure capability must be abstracted behind a domain interface/port and registered as a bean in `config`.
- The API layer calls domain services only, never `infrastructure` directly.
- `ArchitectureTest` enforces these rules; keep it green.
- Authentication is done once in a security filter (JWT or `X-Share-Token`), not repeated in each endpoint.
- Controllers receive the caller as a method parameter, resolved by `CallerArgumentResolver`: `Caller caller` for diagram, version and public endpoints (anonymous when there is no login or link), or `@CurrentUser UUID userId` for endpoints that need an account (401 otherwise). Never read `Authentication` or use `@AuthenticationPrincipal` in a controller.
- Services check diagram permissions only through `DiagramAccess.require(diagramId, caller, Permission)` (`VIEW`, `VIEW_HISTORY`, `EDIT`, `MANAGE`): 404 if missing, 401 if the caller has neither an account nor a link to that diagram, 403 otherwise. Do not write `caller.canX(...)` checks or throw access exceptions by hand in services.

## Database Migrations

- Flyway migrations live in `src/main/resources/db/migration`, named `V<number>__<description>.sql` (two underscores).
- Never edit a migration that has been merged to `main`; add a new migration instead (Flyway verifies checksums and fails on changed files).
- Hibernate runs with `ddl-auto: validate`; the schema is owned by Flyway only.
- Tables used by several members are defined in `V1`. Each member adds changes for their own tables or indexes in a new migration.

## Project Scope (Phase 2)

- Build and evolve the backend REST API for an online UML and ERD diagram service.
- Implement architectural improvements driven by quality attributes (Performance, Scalability, Observability, Reliability, Security) based on the Phase 1 baseline evaluation and roadmap in `README.md`:
  - Query & Database: Index tuning (`pg_trgm`, composite indexes), projection queries to avoid loading 2 MB `data` in listing APIs, keyset pagination.
  - Caching & Data Transfer: In-memory or distributed caching for read-heavy diagrams and share tokens, payload compression (gzip), delta/patch updates.
  - Scalability & Concurrency: Horizontal scaling configurations, connection pool optimization, rate limiting.
  - Observability: Health metrics, structured logging, request latency monitoring.
  - Functional enhancements if required by task: Diagram export to PNG, SVG, PDF, or JSON.
- Each member/agent independently chooses suitable tactics and technologies for their assigned improvement area.
- Do not implement frontend/canvas editing, third-party OAuth, or email password recovery unless explicitly requested.

## Benchmarking & Verification Discipline (Phase 2 Mandate)

- Hardware Environment: All performance comparisons must be benchmarked on the fixed hardware baseline: Kaggle Notebook CPU (4 vCPU, 31 GB RAM) on localhost to eliminate network variance, matching the Phase 1 baseline measurement.
- Before & After Measurement:
  - Never claim a performance improvement without empirical benchmark data before and after the change.
  - Measure RPS, p95/p99 latency, and error rate across standard load steps (10, 50, 100 users) using the project's Locust (or k6) test scenario.
- Automated Testing: Keep unit tests, MockMvc tests, and Testcontainers integration tests green. Run `./gradlew test -PskipE2e` for rapid validation.

## API Contract

- Use the `/api/v1` prefix for all application endpoints.
- Preserve the endpoint paths, HTTP methods, authentication requirements, and owner/editor/viewer permissions defined in `docs/openapi.yaml` and `README.md`. Keep `docs/openapi.yaml` synchronized whenever contracts change.
- Return JSON responses with clear, consistent error details and appropriate HTTP status codes.
- Public access is limited to public diagrams, valid sharing tokens, and the health endpoint. Protect all other resources with authentication.
- A newly created diagram must belong to the authenticated user, accept only `UML` or `ERD`, initialize its data as `{ "nodes": [], "edges": [] }`, and be private by default.

## Security and Authorization

- Never store plaintext passwords. Use the project's established password-hashing and token-handling libraries.
- Validate authentication and resource authorization at the service boundary; do not rely on frontend checks.
- Only owners may create, modify, or revoke sharing entries. Editors may update diagrams and create or restore versions but may not manage sharing.
- Generate cryptographically secure, unique sharing tokens and invalidate them immediately when revoked.
- Do not expose private diagram data, credentials, refresh tokens, or internal implementation details in public responses.
- Validate and constrain request bodies, including the maximum 2 MB diagram JSON payload, before persistence.
- Diagram JSON is a `DiagramData` value object: build it with `DiagramData.of(json)` from user input (validates the `nodes`/`edges` shape and 2 MB) and with `DiagramData.fromStorage(json)` only for data read back from the database. Do not pass raw JSON strings through the domain.

## Data Integrity and Concurrency

- Enforce optimistic locking for diagram updates using the client-supplied `version`.
- Reject stale updates with `409 Conflict` and leave the stored diagram unchanged.
- Treat saved diagram versions as immutable snapshots.
- Before restoring an old version, snapshot the current state, then copy the selected snapshot into the current diagram state atomically.
- Keep authorization checks, version checks, and related writes within the appropriate transaction boundaries.
- Use transactions by rule, not everywhere. Domain services get them through the `TransactionRunner` port:

| Operation | Transaction | Example |
|---|---|---|
| Read, check, then write | Yes (`TransactionRunner`) | restore a version, rotate a refresh token |
| Several rows or tables that must stay consistent | Yes | restore = backup snapshot + overwrite diagram |
| Concurrent requests must queue | Yes, plus a row lock (`SELECT ... FOR UPDATE`) | save or restore a version |
| One write that is already safe on its own | No | delete a share; update a diagram (guarded by `@Version`); change a share role (one conditional `UPDATE`) |
| Pure reads | No | get a diagram, list versions |
| Calls to outside systems (email, HTTP, files) | Never inside a transaction | do them after commit |

- Keep transactions short: each one holds a pooled connection and any row locks until it ends.

## Implementation Practices

- Inspect the existing repository structure, framework conventions, schemas, and error-handling patterns before adding code.
- Reuse existing helpers, middleware, validators, and response types instead of duplicating them.
- Keep controllers/routes thin; place business rules in the appropriate service/domain layer.
- Validate enum values, identifiers, ownership, share permissions, token state, and JSON schema at the API boundary.
- Make migrations and schema changes explicit, reversible where the project supports it, and consistent with existing naming conventions.
- Update OpenAPI/Swagger documentation whenever an endpoint or request/response contract changes.
- Do not introduce unrelated refactors or silently change existing behavior.

## Testing and Verification

- Add or update tests for authentication, authorization, public/private access, sharing-token revocation, invalid payloads, optimistic-lock conflicts, immutable versions, and safe restoration.
- Include both successful and denied paths for owner, editor, viewer, unauthenticated, and public requests.
- Verify status codes and response shapes, not only whether a request succeeds.
- Run the smallest relevant formatter, linter, type checker, and test commands before considering a change complete.
- Use the repository's documented commands and configuration; do not invent replacement tooling without a clear need.

## Documentation and Delivery

- Keep API documentation and implementation behavior synchronized.
- Document intentional architectural changes and quality improvements with clear rationale and benchmark results.
- **Architecture Decision Records (ADRs)**:
  - Write an ADR in `docs/adr/` (in English, numbered sequentially, following `docs/adr/template.md`) and update `docs/adr/README.md` **only** for significant architectural decisions: introducing architectural patterns, adding new technology/libraries, schema/storage redesigns, or quality attribute tactics.
  - **Do NOT** write an ADR for bug fixes, minor code refactoring, variable renaming, formatting, adding unit tests, or small everyday adjustments.
- Report validation commands and any unresolved environment limitations with the completed change.
