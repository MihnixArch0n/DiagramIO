package com.diagramio.domain.diagram;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight domain model representing diagram metadata without the heavy JSON payload.
 * Pure Java record, free from framework dependencies.
 */
public record DiagramSummary(
        UUID id,
        String title,
        DiagramType diagramType,
        Integer version,
        boolean isPublic,
        UUID ownerId,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt) {
}
