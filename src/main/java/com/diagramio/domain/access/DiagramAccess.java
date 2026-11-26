package com.diagramio.domain.access;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.exception.DiagramNotFoundException;
import com.diagramio.domain.exception.ForbiddenException;
import com.diagramio.domain.exception.UnauthorizedException;
import java.util.UUID;

/**
 * Single authority checking "does diagram exist and does caller have permission" across all modules,
 * so diagrams, versions, and shares return consistent error responses:
 * <ul>
 *   <li>Missing diagram: 404;</li>
 *   <li>Denied when caller has neither an account nor a share link to this diagram: 401;</li>
 *   <li>Denied when caller is recognized (has account, or view-only link trying to edit): 403.</li>
 * </ul>
 */
public class DiagramAccess {

    private final DiagramRepository diagramRepository;

    public DiagramAccess(DiagramRepository diagramRepository) {
        this.diagramRepository = diagramRepository;
    }

    /** Loads diagram with full payload data and checks permission; returns diagram if permitted. */
    public Diagram require(UUID diagramId, Caller caller, Permission permission) {
        if (diagramId == null) {
            throw new DiagramNotFoundException("Diagram not found");
        }
        Diagram diagram = diagramRepository.findById(diagramId)
                .orElseThrow(() -> new DiagramNotFoundException("Diagram not found: " + diagramId));
        check(diagram, caller, permission);
        return diagram;
    }

    /** Loads diagram metadata only (without blob storage payload) and checks permission; used for update and delete commands. */
    public Diagram requireMetadata(UUID diagramId, Caller caller, Permission permission) {
        if (diagramId == null) {
            throw new DiagramNotFoundException("Diagram not found");
        }
        Diagram diagram = diagramRepository.findMetadataById(diagramId)
                .orElseThrow(() -> new DiagramNotFoundException("Diagram not found: " + diagramId));
        check(diagram, caller, permission);
        return diagram;
    }

    /** Checks permission against an already loaded diagram. */
    public void check(Diagram diagram, Caller caller, Permission permission) {
        if (permission.isGrantedTo(caller, diagram)) {
            return;
        }
        if (!caller.hasIdentityFor(diagram)) {
            throw new UnauthorizedException("Authentication required");
        }
        throw new ForbiddenException(permission.deniedMessage());
    }
}
