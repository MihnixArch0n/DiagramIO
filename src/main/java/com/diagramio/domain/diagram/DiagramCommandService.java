package com.diagramio.domain.diagram;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.access.DiagramAccess;
import com.diagramio.domain.access.Permission;
import com.diagramio.domain.exception.InvalidInputException;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain command service for diagram operations (create, update, delete).
 * Pure Java domain class, intentionally free of framework dependencies.
 */
public class DiagramCommandService {

    private final DiagramRepository repository;
    private final DiagramAccess access;

    public DiagramCommandService(DiagramRepository repository) {
        this.repository = repository;
        this.access = new DiagramAccess(repository);
    }

    public Diagram createDiagram(Diagram diagram) {
        if (diagram == null) {
            throw new InvalidInputException("Diagram is required");
        }
        requireType(diagram.getDiagramType());
        if (diagram.getData() == null) {
            diagram.setData(DiagramData.EMPTY);
        }

        Instant now = Instant.now();
        diagram.setVersion(1);
        diagram.setPublic(false);
        diagram.setCreatedAt(now);
        diagram.setUpdatedAt(now);
        return repository.save(diagram);
    }

    /**
     * Either the owner or a caller with an EDIT share link can update the diagram.
     * Only the owner can change the public visibility; if an editor via share link sends
     * a different isPublic value, the existing visibility remains unchanged.
     * updatedBy records the authenticated user, or null if updated via link without authentication.
     */
    public Diagram updateDiagram(UUID id, String title, DiagramType diagramType, DiagramData data,
                                 boolean isPublic, Caller caller, Integer requestVersion) {
        if (id == null) {
            throw new InvalidInputException("Diagram id is required");
        }
        Diagram current = access.require(id, caller, Permission.EDIT);
        checkVersion(id, requestVersion, current.getVersion());
        requireType(diagramType);
        if (data == null) {
            throw new InvalidInputException("data must be valid JSON");
        }

        Instant now = Instant.now();
        current.updateMetadata(title, diagramType, caller.userId(), now);
        current.replaceContent(data, caller.userId(), now);
        // Only the owner can change visibility; ignore changes from share link editors
        if (Permission.MANAGE.isGrantedTo(caller, current)) {
            current.setPublic(isPublic);
        }
        return repository.save(current);
    }

    public void deleteDiagram(UUID id, Caller caller) {
        access.require(id, caller, Permission.MANAGE);
        repository.deleteById(id);
    }

    private void checkVersion(UUID id, Integer requestedVersion, Integer currentVersion) {
        if (requestedVersion == null || currentVersion == null || !requestedVersion.equals(currentVersion)) {
            throw new OptimisticLockConflictException(id, requestedVersion, currentVersion);
        }
    }

    private void requireType(DiagramType diagramType) {
        if (diagramType == null) {
            throw new InvalidInputException("diagramType must be UML or ERD");
        }
    }
}
