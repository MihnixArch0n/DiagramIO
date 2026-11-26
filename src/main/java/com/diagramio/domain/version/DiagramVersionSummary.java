package com.diagramio.domain.version;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight domain model representing a version snapshot metadata without the heavy JSON payload.
 * Pure Java record, free from framework dependencies.
 */
public record DiagramVersionSummary(
        UUID id,
        UUID diagramId,
        int versionNumber,
        String note,
        UUID createdBy,
        Instant createdAt) {
}
