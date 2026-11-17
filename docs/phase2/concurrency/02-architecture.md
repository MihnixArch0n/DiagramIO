# Step 2 - Architecture Analysis

## 1. Project Architecture

DiagramIO uses a layered architecture:

- API: Controllers, DTOs, HTTP responses
- Domain: Business logic and access control
- Infrastructure: JPA, repository adapters, PostgreSQL
- Config: Dependency injection and application configuration

## 2. Diagram Read Flow

Client
  -> DiagramController
  -> DiagramService
  -> DiagramAccess
  -> DiagramRepository
  -> DiagramRepositoryAdapter
  -> PostgreSQL

## 3. Existing Concurrency Mechanisms

### Optimistic Locking

- DiagramEntity uses JPA @Version.
- DiagramRepositoryAdapter uses saveAndFlush().
- Version conflicts are detected during concurrent updates.

### Pessimistic Locking

- DiagramVersionService uses lockDiagram().
- Database row locking protects version operations.

### Transaction Management

- TransactionRunner provides transactional execution.

## 4. Identified Performance Improvements

### Cache-Aside

- Use Caffeine for repeated diagram reads.
- Reduce redundant database reads.
- Preserve authorization checks.
- Handle cache invalidation after changes.

### Anti-Stampede

- Prevent multiple concurrent requests from loading
  the same missing cache entry simultaneously.

### Asynchronous Request-Reply

- Process expensive export jobs asynchronously.
- Return a job ID and support status polling.

### Bulkhead

- Use a dedicated bounded worker pool.
- Prevent expensive background jobs from monopolizing
  request-processing resources.

## 5. Security and Consistency Requirements

- Never bypass DiagramAccess.
- Do not reuse revoked share-token permissions.
- Preserve optimistic locking and version consistency.
- Handle diagram update, delete and restore.
- Coordinate repository changes with Member 1.
- Coordinate authorization changes with Member 3.

## 6. Conclusion

The current architecture and concurrency mechanisms
have been analyzed.

Cache-Aside, Anti-Stampede, Async Request-Reply
and Bulkhead are planned Phase 2 improvements.

No Java business logic was modified in Step 2.

Status: COMPLETED
