package com.diagramio.domain.diagram;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.access.DiagramAccess;
import com.diagramio.domain.access.Permission;
import com.diagramio.domain.exception.InvalidInputException;
import java.util.List;
import java.util.UUID;

/**
 * Domain query service for diagram operations (detail, summaries, counts).
 * Read-only operations focused on high-throughput projections without hydrating full payloads.
 * Pure Java domain class, intentionally free of framework dependencies.
 */
public class DiagramQueryService {

    public static final int MAX_PAGE_SIZE = 100;

    private final DiagramRepository repository;
    private final DiagramAccess access;

    public DiagramQueryService(DiagramRepository repository) {
        this.repository = repository;
        this.access = new DiagramAccess(repository);
    }

    public Diagram getDiagramById(UUID id, Caller caller) {
        return access.require(id, caller, Permission.VIEW);
    }

    public List<DiagramSummary> getDiagramSummariesByOwner(UUID ownerId, DiagramType diagramType, String title,
                                                          int page, int size) {
        validatePage(page, size);
        return repository.findSummariesByOwner(ownerId, diagramType, normalizeFilter(title), page, size);
    }

    public long countDiagramsByOwner(UUID ownerId, DiagramType diagramType, String title) {
        return repository.countByOwnerId(ownerId, diagramType, normalizeFilter(title));
    }

    public List<DiagramSummary> getPublicDiagramSummaries(int page, int size) {
        validatePage(page, size);
        return repository.findPublicDiagramSummaries(page, size);
    }

    public long countPublicDiagrams() {
        return repository.countPublicDiagrams();
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidInputException("page must be non-negative and size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private String normalizeFilter(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
