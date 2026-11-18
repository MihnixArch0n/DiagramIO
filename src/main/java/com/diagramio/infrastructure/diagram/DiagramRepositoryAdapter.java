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
import org.springframework.stereotype.Repository;

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
            // New diagram: persist initial record to generate UUID and initialize version = 1
            DiagramEntity entity = mapper.toEntity(diagram, null);
            DiagramEntity saved = repository.saveAndFlush(entity);
            id = saved.getId();
            String storageKey = "diagrams/" + id + "/" + UUID.randomUUID() + ".json";
            blobStorage.store(storageKey, dataJson);
            repository.updateStorageKey(id, storageKey);
            saved.setStorageKey(storageKey);
            saved.setData(null);
            return mapper.toDomain(saved, dataJson);
        }

        // Existing diagram update: write payload to a new snapshot file (prevents overwriting old file on optimistic lock conflict)
        String storageKey = "diagrams/" + id + "/" + UUID.randomUUID() + ".json";
        blobStorage.store(storageKey, dataJson);

        DiagramEntity entity = mapper.toEntity(diagram, storageKey);
        try {
            DiagramEntity saved = repository.saveAndFlush(entity);
            return mapper.toDomain(saved, dataJson);
        } catch (OptimisticLockingFailureException _) {
            Integer currentVersion = repository.findById(diagram.getId()).map(DiagramEntity::getVersion).orElse(null);
            throw new OptimisticLockConflictException(diagram.getId(), diagram.getVersion(), currentVersion);
        }
    }

    @Override
    public Optional<Diagram> findById(UUID id) {
        return repository.findById(id).map(e -> mapper.toDomain(e, blobStorage));
    }

    @Override
    public List<Diagram> findByOwnerId(UUID ownerId, DiagramType diagramType, String title, int page, int size) {
        return findPage(ownerId, diagramType, title, PageRequest.of(page, size))
                .map(e -> mapper.toDomain(e, blobStorage))
                .getContent();
    }

    @Override
    public List<DiagramSummary> findSummariesByOwner(UUID ownerId, DiagramType diagramType, String title, int page, int size) {
        return findSummaryPage(ownerId, diagramType, title, PageRequest.of(page, size))
                .map(this::toSummaryDomain)
                .getContent();
    }

    @Override
    public long countByOwnerId(UUID ownerId, DiagramType diagramType, String title) {
        return findPage(ownerId, diagramType, title, PageRequest.of(0, 1)).getTotalElements();
    }

    @Override
    public List<Diagram> findPublicDiagrams(int page, int size) {
        return repository.findByIsPublicTrue(PageRequest.of(page, size))
                .map(e -> mapper.toDomain(e, blobStorage))
                .getContent();
    }

    @Override
    public List<DiagramSummary> findPublicDiagramSummaries(int page, int size) {
        return repository.findSummaryByIsPublicTrue(PageRequest.of(page, size))
                .map(this::toSummaryDomain)
                .getContent();
    }

    @Override
    public long countPublicDiagrams() {
        return repository.findByIsPublicTrue(PageRequest.of(0, 1)).getTotalElements();
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

    private Page<DiagramSummaryRecord> findSummaryPage(
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
