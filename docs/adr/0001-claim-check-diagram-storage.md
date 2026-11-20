# ADR-0001: Claim-Check Pattern for Diagram Payload Storage

**Date**: 2026-11-19  
**Status**: accepted  
**Deciders**: Member 1 (Data & Storage Architecture), DiagramIO Team  

## Context

In DiagramIO Phase 1, diagram contents (nodes and edges up to 2MB JSON per diagram or snapshot) were stored directly inside PostgreSQL `jsonb` columns in `diagrams` and `diagram_versions`.

Because PostgreSQL tuples have an 8KB page limit and a TOAST threshold of ~2KB, diagram payloads are compressed and moved out-of-line into `pg_toast` tables. This causes critical architectural bottlenecks:
1. **Database Bloat & I/O Overhead**: Every diagram modification or version snapshot writes large TOAST chunks. Table and TOAST bloat degrades VACUUM performance, WAL generation, and disk I/O.
2. **Buffer Pool Pollution**: Accessing diagrams forces PostgreSQL shared buffers to cache large TOAST slices instead of index pages and relational tuples.
3. **Multiplier Effect on Version History**: Each entry in `diagram_versions` is an immutable snapshot duplicating up to 2MB of JSON. Fifty versions of a diagram produce 100MB of TOAST data in the relational database.

We need an architectural tactic to store heavy diagram payloads externally while maintaining relational integrity, access control, transactional consistency, and Clean Architecture layer isolation.

## Decision

We adopt the **Claim-Check Pattern** for diagram payload storage:

1. **Domain Port Abstraction**: Introduce `BlobStoragePort` in `com.diagramio.domain.diagram` providing pure Java contracts for `store(key, content)`, `load(key)`, `delete(key)`, and `deleteAllPrefix(prefix)`.
2. **Infrastructure Storage Adapter**: Implement `FileSystemBlobStorageAdapter` in `com.diagramio.infrastructure.storage` to persist JSON payloads as files on persistent disk/volume, structured hierarchically by diagram ID (`blobs/diagrams/{diagramId}/{key}.json`) with atomic writes (`ATOMIC_MOVE`).
3. **Transparent Persistence Integration**: Isolate the Claim-Check mechanism inside `DiagramRepositoryAdapter` and `DiagramVersionRepositoryAdapter`. Domain models (`Diagram`, `DiagramVersion`) and Domain services (`DiagramService`, `DiagramVersionService`) continue to work with `DiagramData` without knowing about storage files.
4. **Relational Schema Evolution**: Via Flyway migration `V5__claim_check_storage_keys.sql`, add `storage_key VARCHAR(255)` to `diagrams` and `diagram_versions`, and relax `data` column to nullable. New records write payloads to blob storage and store `storage_key` with `data = NULL` in PostgreSQL. Existing legacy rows without `storage_key` gracefully fallback to reading from the `data` column.
5. **Lifecycle Synchronization**: When a diagram is deleted (`deleteById`), all associated blob storage files under `diagrams/{diagramId}` are recursively purged.

## Alternatives Considered

### Alternative 1: Keep payloads inside PostgreSQL using `STORAGE EXTERNAL` or `pg_largeobject`
- **Pros**: Kept entirely within PostgreSQL; transaction rollback automatically rolls back large objects.
- **Cons**: Still bloats database backups, replication streams, and PostgreSQL shared buffer memory; does not solve TOAST/WAL saturation.
- **Why not**: Retaining 2MB JSON within the database engine misses the primary Quality Attribute goal of offloading bulk payload storage from PostgreSQL.

### Alternative 2: Direct Cloud Object Storage SDK (AWS S3 / MinIO) in Domain Layer
- **Pros**: Direct integration with cloud storage.
- **Cons**: Violates Clean Architecture by binding domain models to external cloud SDKs and network protocols.
- **Why not**: Clean Architecture mandates that external I/O and storage drivers reside in the Infrastructure layer behind a Domain Port. `FileSystemBlobStorageAdapter` can be replaced with an S3 adapter in the future without changing a single line of business code.

### Alternative 3: Explicit Claim-Check in API / Domain Service
- **Pros**: Controllers or domain services explicitly see storage keys.
- **Cons**: Breaks API contract compatibility (`docs/openapi.yaml`) which requires `data` in diagram detail responses, and pollutes domain services with file I/O coordination.
- **Why not**: Transparent persistence in repository adapters preserves the existing REST API contract and keeps the domain core pure.

## Consequences

### Positive
- **TOAST Elimination**: Completely eliminates TOAST table writes for diagrams and version snapshots, dramatically reducing PostgreSQL table size, WAL write volume, and buffer pool consumption.
- **High-Performance List & Query**: Relational queries (`SELECT`, index scans, joins) operate exclusively on lightweight metadata rows without touching large disk blocks.
- **Clean Architecture Compliant**: Domain models, business rules, and API contracts remain unchanged; `ArchitectureTest` remains 100% green.
- **Forward-Compatible**: Pluggable storage adapter allows seamless future migration to S3, GCS, or Ceph without altering domain services.

### Negative
- **Dual Storage Management**: Application must manage both PostgreSQL relational state and filesystem storage.
- **Backup Coordination**: Full backups require coordinating PostgreSQL database dumps with the blob storage directory/volume.

### Risks & Mitigations
- **Partial Write / Incomplete Files**: Writing directly to the destination file could cause corrupt reads during crash or concurrent access.
  - *Mitigation*: `FileSystemBlobStorageAdapter` writes to a temporary file first and atomically renames it via `StandardCopyOption.ATOMIC_MOVE`.
- **Orphaned Files on Delete**: Deleting a row from the database could leave unused files taking up disk space.
  - *Mitigation*: `DiagramRepositoryAdapter.deleteById` invokes `blobStorage.deleteAllPrefix("diagrams/" + id)` to purge all associated files.
