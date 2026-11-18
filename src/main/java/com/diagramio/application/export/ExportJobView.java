package com.diagramio.application.export;

import java.time.Instant;
import java.util.UUID;

public record ExportJobView(
        UUID jobId,
        UUID diagramId,
        ExportJobStatus status,
        Instant createdAt,
        Instant completedAt,
        String errorCode
) {
}
