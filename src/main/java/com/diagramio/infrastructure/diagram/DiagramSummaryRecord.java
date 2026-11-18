package com.diagramio.infrastructure.diagram;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA Record projection for diagram metadata queries, completely excluding the 2MB JSON data column.
 */
public record DiagramSummaryRecord(
        UUID id,
        String title,
        String diagramType,
        Integer version,
        boolean isPublic,
        UUID ownerId,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt) {
}
