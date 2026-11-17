# Step 4 - Cache-Aside & Anti-Stampede Design

## 1. Status

DESIGN COMPLETED - IMPLEMENTATION NOT STARTED

Base commit: 6791e11
Branch: feature/phase2-concurrency

Owner: Member 2 - High Concurrency, Async & Resilience

## 2. Problem Statement

Current shared diagram reads use PostgreSQL for
share-token validation and full diagram retrieval.

The measured Before benchmark is:

| Users | RPS | p95 | p99 | Errors |
|---|---:|---:|---:|---:|
| 10 | 87.33 | 16 ms | 25 ms | 0% |
| 50 | 404.61 | 29 ms | 45 ms | 0% |
| 100 | 474.12 | 93 ms | 120 ms | 0% |

Workload:
- 20 ERD diagrams
- Approximately 123.6 KiB per diagram
- 80% shared reads, 20% diagram reads
- Windows + Docker + PostgreSQL 18
- Locust 2.47.0

The observed scaling limit is not yet attributed
to a specific bottleneck.

## 3. Scope

Initial cache target:

GET /api/v1/shares/{token}

The following read path remains unchanged initially:

GET /api/v1/diagrams/{id}

Write operations and JPA optimistic locking
remain unchanged.

## 4. Selected Pattern

Version-aware Cache-Aside using Caffeine.

Request flow:

1. Validate share token against the database.
2. Obtain the current diagram version from PostgreSQL.
3. Build cache key (diagramId, version).
4. Return immutable cached snapshot on HIT.
5. On MISS, read the full diagram and verify version.
6. Cache only data whose version matches the key.
7. Combine snapshot with current share role.
8. Return the original API response contract.

Never cache share-token authorization decisions.

## 5. Cache Key

DiagramCacheKey(UUID diagramId, int version)

The cache must not use a token or Caller as its key.

Different valid tokens may share the same cached
diagram content, but each token must be checked
against current database authorization data.

## 6. Cache Value

Use an immutable DiagramReadSnapshot containing
the fields required for the shared diagram response.

Do not cache mutable Diagram objects or JPA entities.

ShareRole is read from the current share record
and must not be part of the cached snapshot.

## 7. Caffeine Configuration

Initial experiment settings:

- expireAfterWrite: 2 minutes
- maximumWeight: approximately 64 MiB
- weigher: approximate JSON payload size plus overhead
- recordStats: enabled
- single-flight: atomic Cache.get(key, loader)

These are experimental values, not final tuning.

Cache weights approximate retained size.
They are not exact Java heap measurements.

## 8. Anti-Stampede

Use atomic Caffeine Cache.get(key, mappingFunction).

Concurrent misses for the same key should share
one cache load rather than independently query DB.

Do not perform recursive writes to the cache
inside the mapping function.

A cache miss across different keys may still
cause multiple concurrent database queries.

## 9. Data Consistency

Current DB version determines the cache key.

Update:
- JPA increases diagram version.
- New reads use the new key.

Restore:
- JPA increases diagram version.
- New reads use the new key.

Delete:
- Missing DB revision returns 404.

Share revoke:
- The token is checked in the database each time.
- Revoked tokens cannot access cached data in
  requests starting after revocation has committed.

Share role update:
- Current role comes from the share table.

Rollback:
- Do not expose uncommitted values in cache.

Creation of a diagram-version snapshot alone does
not change the current diagram content.

Old cache entries expire automatically.
Optional post-commit invalidation can reclaim
old versions earlier.

Correctness does not rely solely on invalidation.

## 10. Concurrency Race Handling

If the revision query returns version V but
the full database read returns another version:

- Do not cache the mismatched snapshot.
- Retry version lookup with a bounded retry count.
- On persistent contention, use an uncached DB read.

Cache misses and concurrency races must not
silently change the API error contract.

Requests overlapping a concurrent write or
revocation may observe timing-dependent results.
This design does not claim full linearizability.

## 11. Proposed Domain Types

New:

- DiagramCacheKey
- DiagramReadSnapshot
- DiagramRevisionReader
- DiagramReadCache
- CachedDiagramReader

Domain code must remain plain Java without
Spring or Caffeine dependencies.

## 12. Proposed Infrastructure Types

New:

- JpaDiagramRevisionReader
- CaffeineDiagramReadCache

JpaDiagramRevisionReader performs a lightweight
version lookup without loading JSONB.

Avoid modifying DiagramRepositoryAdapter or
DiagramJpaRepository owned by Member 1.

## 13. Integration

New:

- DiagramCacheConfig

Modify during implementation:

- ShareService
- ShareConfig
- build.gradle.kts
- gradle/libs.versions.toml

Keep existing unit-test constructors compatible
or migrate tests explicitly.

Do not modify the existing write path in the
first cache implementation.

## 14. Test Plan

CACHE-01: First read -> MISS
CACHE-02: Repeated read -> HIT
CACHE-03: 50 concurrent misses -> one loader call
CACHE-04: Update -> new version
CACHE-05: Restore -> current restored content
CACHE-06: Delete -> 404
CACHE-07: Revoke share -> access denied
CACHE-08: Change share role -> current role
CACHE-09: Optimistic lock conflict -> 409
CACHE-10: Read/write race -> no mismatched cache entry
CACHE-11: TTL expiration -> reload
CACHE-12: Restart -> functional DB fallback

## 15. Measurement Plan

Re-run the exact Step 3 Locust workload:

- 10, 50, 100 users
- Same 20 diagram fixtures
- Same payloads
- Same endpoint mix
- Same host and Docker configuration

Record:
- RPS
- p95, p99
- Error rate
- Cache hit/miss/load/eviction statistics
- CPU and RAM

Repeat tests to reduce run-to-run noise.

## 16. Architectural Tradeoffs

Advantages:
- Avoids repeated large JSONB reads on cache HIT
- Reuses JPA @Version for consistency
- Does not cache authorization state
- Leaves the write repository unchanged
- Supports single-flight cache loading

Limitations:
- Still performs DB queries for token and version
- Additional version query can offset speed gains
- Cache uses application memory
- Single-flight applies per key
- Cache is local to each JVM instance
- Multi-instance deployments need careful testing
- Concurrent in-flight requests are not linearizable

## 17. Team Boundaries

Member 1:
- Database schema, JPA optimization, projections

Member 2:
- Cache ports, Caffeine implementation
- Anti-stampede, cache tests, benchmarks

Member 3:
- Security and share-token behavior

Coordinate before changing shared ownership files.

## 18. Definition of Done - Step 4

- Cache scope selected
- Cache key and value defined
- Authorization boundary documented
- TTL and memory policy selected
- Anti-stampede strategy documented
- Consistency and invalidation strategy documented
- Implementation file map written
- Test plan documented
- Design committed and pushed

No Java production code is changed in Step 4.

## 19. Next Step

Step 5: Implement Caffeine Cache-Aside.
