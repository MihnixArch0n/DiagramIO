package com.diagramio.infrastructure.version;

import com.diagramio.domain.diagram.BlobStoragePort;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.exception.ConflictException;
import com.diagramio.domain.version.DiagramVersion;
import com.diagramio.domain.version.DiagramVersionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class DiagramVersionRepositoryAdapter implements DiagramVersionRepository {

    private final DiagramVersionJpaRepository jpaRepository;
    private final EntityManager entityManager;
    private final BlobStoragePort blobStorage;

    public DiagramVersionRepositoryAdapter(
            DiagramVersionJpaRepository jpaRepository,
            EntityManager entityManager,
            BlobStoragePort blobStorage) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
        this.blobStorage = blobStorage;
    }

    @Override
    public void lockDiagram(UUID diagramId) {
        jpaRepository.lockDiagramRow(diagramId);
    }

    @Override
    public DiagramVersion save(DiagramVersion version) {
        // persist (not merge): snapshots are append-only, never overwritten
        // Use Claim-Check pattern: offload snapshot payload to BlobStoragePort
        String storageKey = "diagrams/" + version.diagramId() + "/versions/" + version.id() + ".json";
        String dataJson = (version.data() != null) ? version.data().json() : DiagramData.EMPTY.json();
        blobStorage.store(storageKey, dataJson);

        DiagramVersionEntity entity = DiagramVersionEntity.fromDomain(version, storageKey);
        try {
            entityManager.persist(entity);
            entityManager.flush();
        } catch (PersistenceException ex) {
            blobStorage.delete(storageKey);
            // Safety net: normally lockDiagram serializes requests to avoid conflicting version numbers
            if (ex instanceof ConstraintViolationException || ex.getCause() instanceof ConstraintViolationException) {
                throw new ConflictException("Another version was saved at the same time, please retry");
            }
            throw ex;
        }
        return entity.toDomain(blobStorage);
    }

    @Override
    public List<DiagramVersion> findByDiagramId(UUID diagramId) {
        return jpaRepository.findByDiagramIdOrderByVersionNumberDesc(diagramId).stream()
                .map(e -> e.toDomain(blobStorage))
                .toList();
    }

    @Override
    public Optional<DiagramVersion> findByIdAndDiagramId(UUID id, UUID diagramId) {
        return jpaRepository.findByIdAndDiagramId(id, diagramId).map(e -> e.toDomain(blobStorage));
    }

    @Override
    public int maxVersionNumber(UUID diagramId) {
        return jpaRepository.maxVersionNumber(diagramId);
    }
}
