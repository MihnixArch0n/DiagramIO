package com.diagramio.domain.diagram;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot for read caching.
 *
 * Does not contain share token, Caller or ShareRole.
 * Authorization must be checked on every request.
 */
public record DiagramReadSnapshot(
        UUID diagramId,
        String title,
        DiagramType diagramType,
        DiagramData data,
        int version,
        boolean isPublic,
        Instant createdAt,
        Instant updatedAt
) {

    public DiagramReadSnapshot {
        Objects.requireNonNull(
                diagramId, "diagramId must not be null");
        Objects.requireNonNull(
                title, "title must not be null");
        Objects.requireNonNull(
                diagramType, "diagramType must not be null");
        Objects.requireNonNull(
                data, "data must not be null");

        if (version < 0) {
            throw new IllegalArgumentException(
                    "version must not be negative");
        }
    }

    public static DiagramReadSnapshot from(Diagram diagram) {
        Objects.requireNonNull(
                diagram, "diagram must not be null");

        Integer currentVersion = Objects.requireNonNull(
                diagram.getVersion(),
                "diagram version must not be null");

        return new DiagramReadSnapshot(
                diagram.getId(),
                diagram.getTitle(),
                diagram.getDiagramType(),
                diagram.getData(),
                currentVersion,
                diagram.isPublic(),
                diagram.getCreatedAt(),
                diagram.getUpdatedAt()
        );
    }
}
