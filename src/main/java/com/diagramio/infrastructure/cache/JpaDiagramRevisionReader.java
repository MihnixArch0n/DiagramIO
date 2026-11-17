package com.diagramio.infrastructure.cache;

import com.diagramio.domain.diagram.DiagramRevisionReader;
import com.diagramio.infrastructure.diagram.DiagramEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only adapter for the current JPA diagram version.
 *
 * Uses a scalar JPQL projection instead of loading the
 * full DiagramEntity, especially its large JSONB payload.
 */
@Repository
public class JpaDiagramRevisionReader
        implements DiagramRevisionReader {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public OptionalInt findCurrentVersion(UUID diagramId) {
        Objects.requireNonNull(
                diagramId,
                "diagramId must not be null"
        );

        return entityManager
                .createQuery(
                    "SELECT d.version FROM DiagramEntity d "
                    + "WHERE d.id = :diagramId",
                    Integer.class
                )
                .setParameter("diagramId", diagramId)
                .getResultStream()
                .mapToInt(Integer::intValue)
                .findFirst();
    }
}
