package com.diagramio.infrastructure.diagram;

import com.diagramio.domain.diagram.BlobStoragePort;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.diagram.DiagramSummary;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class DiagramRepositoryAdapter implements DiagramRepository {

    private final DiagramJpaRepository repository;
    private final DiagramMapper mapper;
    private final BlobStoragePort blobStorage;

    public DiagramRepositoryAdapter(
            DiagramJpaRepository repository,
            DiagramMapper mapper,
            BlobStoragePort blobStorage) {
        this.repository = repository;
        this.mapper = mapper;
        this.blobStorage = blobStorage;
    }

    /**
     * Immediately writes (saveAndFlush) so @Version checks and increments version in the same call:
     * returns 409 if another writer updated first, rather than failing at commit time.
     * Uses Claim-Check pattern: offloads JSON payload data to BlobStoragePort
     * and only stores storage_key in PostgreSQL table.
     */
    @Override
    public Diagram save(Diagram diagram) {
        String dataJson = diagram.getData() != null ? diagram.getData().json() : DiagramData.EMPTY.json();
        UUID id = diagram.getId();

        if (id == null) {
            // New diagram: persist initial record with null data to eliminate TOAST table bloat
            DiagramEntity entity = mapper.toEntity(diagram, null);
            entity.setData(null);
            DiagramEntity saved = repository.saveAndFlush(entity);
            id = saved.getId();
            String storageKey = "diagrams/" + id + "/" + UUID.randomUUID() + ".json";
            try {
                blobStorage.store(storageKey, dataJson);
                repository.updateStorageKey(id, storageKey);
                saved.setStorageKey(storageKey);
                saved.setData(null);
                registerRollbackBlobCleanup(storageKey);
                return mapper.toDomain(saved, dataJson);
            } catch (Throwable ex) {
                blobStorage.delete(storageKey);
                repository.deleteById(id);
                if (ex instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(ex);
            }
        }

        // Existing diagram update: write payload to a new snapshot file
        String oldStorageKey = repository.findById(id).map(DiagramEntity::getStorageKey).orElse(null);
        String storageKey = "diagrams/" + id + "/" + UUID.randomUUID() + ".json";
        blobStorage.store(storageKey, dataJson);

        DiagramEntity entity = mapper.toEntity(diagram, storageKey);
        try {
            DiagramEntity saved = repository.saveAndFlush(entity);
            if (oldStorageKey != null && !oldStorageKey.equals(storageKey)) {
                scheduleOldBlobDeletion(oldStorageKey);
            }
            registerRollbackBlobCleanup(storageKey);
            return mapper.toDomain(saved, dataJson);
        } catch (OptimisticLockingFailureException _) {
            blobStorage.delete(storageKey);
            Integer currentVersion = repository.findById(diagram.getId()).map(DiagramEntity::getVersion).orElse(null);
            throw new OptimisticLockConflictException(diagram.getId(), diagram.getVersion(), currentVersion);
        } catch (Throwable ex) {
            blobStorage.delete(storageKey);
            if (ex instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(ex);
        }
    }

    private void scheduleOldBlobDeletion(String oldStorageKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    blobStorage.delete(oldStorageKey);
                }
            });
        } else {
            blobStorage.delete(oldStorageKey);
        }
    }

    private void registerRollbackBlobCleanup(String storageKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        blobStorage.delete(storageKey);
                    }
                }
            });
        }
    }

    @Override
    public Optional<Diagram> findById(UUID id) {
        return repository.findById(id).map(e -> mapper.toDomain(e, blobStorage));
    }

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "updatedAt");

    @Override
    public List<Diagram> findByOwnerId(UUID ownerId, DiagramType diagramType, String title, int page, int size) {
        return findPage(ownerId, diagramType, title, PageRequest.of(page, size, DEFAULT_SORT))
                .map(e -> mapper.toDomain(e, blobStorage))
                .getContent();
    }

    @Override
    public List<DiagramSummary> findSummariesByOwner(UUID ownerId, DiagramType diagramType, String title, int page, int size) {
        return findSummaryRecords(ownerId, diagramType, title, PageRequest.of(page, size, DEFAULT_SORT)).stream()
                .map(this::toSummaryDomain)
                .toList();
    }

    @Override
    public long countByOwnerId(UUID ownerId, DiagramType diagramType, String title) {
        boolean hasDiagramType = diagramType != null;
        boolean hasTitle = title != null && !title.isBlank();

        if (hasDiagramType && hasTitle) {
            return repository.countByOwnerIdAndDiagramTypeAndTitleContainingIgnoreCase(
                    ownerId, diagramType.name(), title);
        }
        if (hasDiagramType) {
            return repository.countByOwnerIdAndDiagramType(ownerId, diagramType.name());
        }
        if (hasTitle) {
            return repository.countByOwnerIdAndTitleContainingIgnoreCase(ownerId, title);
        }
        return repository.countByOwnerId(ownerId);
    }

    @Override
    public List<Diagram> findPublicDiagrams(int page, int size) {
        return repository.findByIsPublicTrue(PageRequest.of(page, size, DEFAULT_SORT))
                .map(e -> mapper.toDomain(e, blobStorage))
                .getContent();
    }

    @Override
    public List<DiagramSummary> findPublicDiagramSummaries(int page, int size) {
        return repository.findSummaryByIsPublicTrue(PageRequest.of(page, size, DEFAULT_SORT)).stream()
                .map(this::toSummaryDomain)
                .toList();
    }

    @Override
    public long countPublicDiagrams() {
        return repository.countByIsPublicTrue();
    }

    @Override
    public void deleteById(UUID id) {
        repository.deleteById(id);
        blobStorage.deleteAllPrefix("diagrams/" + id);
    }

    private Page<DiagramEntity> findPage(
            UUID ownerId, DiagramType diagramType, String title, PageRequest pageRequest) {
        boolean hasDiagramType = diagramType != null;
        boolean hasTitle = title != null && !title.isBlank();

        if (hasDiagramType && hasTitle) {
            return repository.findByOwnerIdAndDiagramTypeAndTitleContainingIgnoreCase(
                    ownerId, diagramType.name(), title, pageRequest);
        }
        if (hasDiagramType) {
            return repository.findByOwnerIdAndDiagramType(ownerId, diagramType.name(), pageRequest);
        }
        if (hasTitle) {
            return repository.findByOwnerIdAndTitleContainingIgnoreCase(ownerId, title, pageRequest);
        }
        return repository.findByOwnerId(ownerId, pageRequest);
    }

    private List<DiagramSummaryRecord> findSummaryRecords(
            UUID ownerId, DiagramType diagramType, String title, PageRequest pageRequest) {
        boolean hasDiagramType = diagramType != null;
        boolean hasTitle = title != null && !title.isBlank();

        if (hasDiagramType && hasTitle) {
            return repository.findSummaryByOwnerIdAndDiagramTypeAndTitleContainingIgnoreCase(
                    ownerId, diagramType.name(), title, pageRequest);
        }
        if (hasDiagramType) {
            return repository.findSummaryByOwnerIdAndDiagramType(ownerId, diagramType.name(), pageRequest);
        }
        if (hasTitle) {
            return repository.findSummaryByOwnerIdAndTitleContainingIgnoreCase(ownerId, title, pageRequest);
        }
        return repository.findSummaryByOwnerId(ownerId, pageRequest);
    }

    private DiagramSummary toSummaryDomain(DiagramSummaryRecord record) {
        return new DiagramSummary(
                record.id(),
                record.title(),
                DiagramType.from(record.diagramType()),
                record.version(),
                record.isPublic(),
                record.ownerId(),
                record.updatedBy(),
                record.createdAt(),
                record.updatedAt());
    }
}
