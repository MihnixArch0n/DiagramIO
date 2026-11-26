package com.diagramio.domain.diagram;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiagramRepository {
    Diagram save(Diagram diagram);

    Optional<Diagram> findById(UUID id);

    /** Loads diagram metadata without hydrating payload JSON from blob storage; used for permission checks and metadata. */
    Optional<Diagram> findMetadataById(UUID id);

    List<DiagramSummary> findSummariesByOwner(UUID ownerId, DiagramType diagramType, String title, int page, int size);

    long countByOwnerId(UUID ownerId, DiagramType diagramType, String title);

    List<DiagramSummary> findPublicDiagramSummaries(int page, int size);

    long countPublicDiagrams();

    void deleteById(UUID id);
}
