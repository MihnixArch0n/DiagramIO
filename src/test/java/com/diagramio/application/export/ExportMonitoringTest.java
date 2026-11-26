package com.diagramio.application.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.diagramio.config.DiagramioObservabilityConfig;
import com.diagramio.domain.access.Caller;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramQueryService;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.infrastructure.cache.CaffeineDiagramReadCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExportMonitoringTest {

    @Test
    void successfulExportIncrementsCounterAndRecordsDuration() throws InterruptedException {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DiagramQueryService diagrams = mock(DiagramQueryService.class);
        ExportJobService exports = new ExportJobService(diagrams, 1, 2, 3, meters);
        try {
            UUID id = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            when(diagrams.getDiagramById(eq(id), any(Caller.class)))
                    .thenReturn(diagram(id, owner, true));
            UUID jobId = exports.submit(id, owner).jobId();
            assertEquals(ExportJobStatus.SUCCEEDED, awaitTerminal(exports, jobId, owner));
            assertEquals(1.0, meters.get("diagramio.export.jobs.succeeded").counter().count());
            assertEquals(0.0, meters.get("diagramio.export.jobs.failed").counter().count());
            assertEquals(1, meters.get("diagramio.export.duration").timer().count());
            assertEquals(1.0, meters.get("diagramio.export.workers.limit").gauge().value());
            assertEquals(2.0, meters.get("diagramio.export.queue.capacity").gauge().value());
            assertNotNull(meters.find("diagramio.export.jobs.rejected").functionCounter());
        } finally {
            exports.shutdown();
            meters.close();
        }
    }

    @Test
    void failedExportIncrementsFailureCounterAndRecordsDuration() throws InterruptedException {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DiagramQueryService diagrams = mock(DiagramQueryService.class);
        ExportJobService exports = new ExportJobService(diagrams, 1, 2, 3, meters);
        try {
            UUID id = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            // Submit verifies ownership with a valid diagram; worker encounters bad data.
            when(diagrams.getDiagramById(eq(id), any(Caller.class)))
                    .thenReturn(diagram(id, owner, true), diagram(id, owner, false));
            UUID jobId = exports.submit(id, owner).jobId();
            assertEquals(ExportJobStatus.FAILED, awaitTerminal(exports, jobId, owner));
            assertEquals(0.0, meters.get("diagramio.export.jobs.succeeded").counter().count());
            assertEquals(1.0, meters.get("diagramio.export.jobs.failed").counter().count());
            assertEquals(1, meters.get("diagramio.export.duration").timer().count());
        } finally {
            exports.shutdown();
            meters.close();
        }
    }

    @Test
    void cacheMetersAreRegistered() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            var config = new DiagramioObservabilityConfig();
            config.diagramioCacheMeterBinder(new CaffeineDiagramReadCache()).bindTo(meters);
            assertNotNull(meters.find("diagramio.cache.hit.ratio").gauge());
            assertNotNull(meters.find("diagramio.cache.entries").gauge());
            assertNotNull(meters.find("diagramio.cache.evictions").functionCounter());
        } finally {
            meters.close();
        }
    }

    private static ExportJobStatus awaitTerminal(ExportJobService exports, UUID jobId, UUID owner)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            ExportJobStatus status = exports.status(jobId, owner).status();
            if (status == ExportJobStatus.SUCCEEDED || status == ExportJobStatus.FAILED) {
                return status;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Export did not reach a terminal state");
    }

    private static Diagram diagram(UUID id, UUID owner, boolean validData) {
        Diagram diagram = new Diagram();
        diagram.setId(id);
        diagram.setOwnerId(owner);
        diagram.setTitle("Monitoring test");
        diagram.setDiagramType(DiagramType.ERD);
        diagram.setVersion(1);
        if (validData) {
            diagram.setData(DiagramData.of("{\"nodes\":[],\"edges\":[]}"));
        }
        return diagram;
    }
}
