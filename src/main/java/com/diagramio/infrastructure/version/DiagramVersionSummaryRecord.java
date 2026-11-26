package com.diagramio.infrastructure.version;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA Projection Record for version snapshot metadata queries, completely excluding payload JSON.
 */
public record DiagramVersionSummaryRecord(
        UUID id,
        UUID diagramId,
        int versionNumber,
        String note,
        UUID createdBy,
        Instant createdAt) {
}
