package com.diagramio.application.export;

/** Process-local executor snapshot; active/queued counts may change immediately after reading. */
public record ExportBulkheadStats(
        int workerLimit,
        int activeWorkers,
        int queuedJobs,
        int queueCapacity,
        long rejectedJobs,
        long completedJobs,
        int retainedJobs
) { }
