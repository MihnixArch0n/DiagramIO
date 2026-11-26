
package com.diagramio.infrastructure.export;

import com.diagramio.application.export.ExportOwnershipReader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaExportOwnershipReader implements ExportOwnershipReader {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public Optional<Boolean> isOwner(UUID diagramId, UUID ownerId) {
        Objects.requireNonNull(diagramId);
        Objects.requireNonNull(ownerId);

        return entityManager.createQuery(
                        "SELECT CASE WHEN d.ownerId = :ownerId "
                                + "THEN true ELSE false END "
                                + "FROM DiagramEntity d WHERE d.id = :diagramId",
                        Boolean.class
                )
                .setParameter("diagramId", diagramId)
                .setParameter("ownerId", ownerId)
                .getResultStream()
                .findFirst();
    }
}
