package com.diagramio.application.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramQueryService;
import com.diagramio.domain.diagram.DiagramType;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ExportBulkheadTest {

    @Test
    void queueFullRejectsWith429WithoutLosingAcceptedJobs() throws Exception {
        DiagramQueryService diagrams = mock(DiagramQueryService.class);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        CountDownLatch enteredWorker = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        when(diagrams.getDiagramById(eq(id), any(Caller.class))).thenAnswer(invocation -> {
            if (Thread.currentThread().getName().startsWith("diagramio-export-")) {
                enteredWorker.countDown();
                if (!releaseWorker.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test worker wait expired");
                }
            }
            return diagram(id, owner, true);
        });

        ExportJobService exports = new ExportJobService(diagrams, 1, 1, 10);
        try {
            UUID first = exports.submit(id, owner).jobId();
            assertTrue(enteredWorker.await(3, TimeUnit.SECONDS), "worker did not start");
            UUID second = exports.submit(id, owner).jobId();

            ExportBulkheadStats full = exports.bulkheadStats();
            assertEquals(1, full.workerLimit());
            assertEquals(1, full.activeWorkers());
            assertEquals(1, full.queuedJobs());
            assertEquals(1, full.queueCapacity());
            assertEquals(2, full.retainedJobs());
            assertEquals(ExportJobStatus.QUEUED, exports.status(second, owner).status());

            ResponseStatusException busy = assertThrows(ResponseStatusException.class,
                    () -> exports.submit(id, owner));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, busy.getStatusCode());
            assertEquals(1, exports.bulkheadStats().rejectedJobs());
            assertEquals(2, exports.bulkheadStats().retainedJobs(), "rejected jobs must not leak");

            releaseWorker.countDown();
            assertEquals(ExportJobStatus.SUCCEEDED, awaitTerminal(exports, first, owner));
            assertEquals(ExportJobStatus.SUCCEEDED, awaitTerminal(exports, second, owner));
            long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (exports.bulkheadStats().completedJobs() < 2 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertTrue(exports.bulkheadStats().completedJobs() >= 2);
        } finally {
            releaseWorker.countDown();
            exports.shutdown();
        }
    }

    @Test
    void retainedJobLimitRejectsEvenWhenQueueHasCapacity() throws Exception {
        DiagramQueryService diagrams = mock(DiagramQueryService.class);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(diagrams.getDiagramById(eq(id), any(Caller.class)))
                .thenReturn(diagram(id, owner, true));

        ExportJobService exports = new ExportJobService(diagrams, 1, 4, 5);
        try {
            for (int n = 0; n < 5; n++) {
                UUID jobId = exports.submit(id, owner).jobId();
                assertEquals(ExportJobStatus.SUCCEEDED, awaitTerminal(exports, jobId, owner));
            }
            assertEquals(5, exports.bulkheadStats().retainedJobs());
            assertEquals(0, exports.bulkheadStats().queuedJobs());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ResponseStatusException.class,
                    () -> exports.submit(id, owner)).getStatusCode());
            assertEquals(1, exports.bulkheadStats().rejectedJobs());
        } finally {
            exports.shutdown();
        }
    }

    @Test
    void workerContinuesAfterOneExportFails() throws Exception {
        DiagramQueryService diagrams = mock(DiagramQueryService.class);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        AtomicInteger workerReads = new AtomicInteger();
        when(diagrams.getDiagramById(eq(id), any(Caller.class))).thenAnswer(invocation -> {
            if (Thread.currentThread().getName().startsWith("diagramio-export-")
                    && workerReads.incrementAndGet() == 1) {
                return diagram(id, owner, false);
            }
            return diagram(id, owner, true);
        });

        ExportJobService exports = new ExportJobService(diagrams, 1, 1, 10);
        try {
            UUID failed = exports.submit(id, owner).jobId();
            assertEquals(ExportJobStatus.FAILED, awaitTerminal(exports, failed, owner));
            assertEquals("EXPORT_FAILED", exports.status(failed, owner).errorCode());

            UUID recovered = exports.submit(id, owner).jobId();
            assertEquals(ExportJobStatus.SUCCEEDED, awaitTerminal(exports, recovered, owner));
            assertTrue(exports.download(recovered, owner).length > 0);
        } finally {
            exports.shutdown();
        }
    }

    @Test
    void productionDefaultsUseFourWorkersAndTwentyQueueSlots() {
        ExportJobService exports = new ExportJobService(mock(DiagramQueryService.class));
        try {
            ExportBulkheadStats stats = exports.bulkheadStats();
            assertEquals(4, stats.workerLimit());
            assertEquals(20, stats.queueCapacity());
            assertEquals(0, stats.activeWorkers());
            assertEquals(0, stats.queuedJobs());
        } finally {
            exports.shutdown();
        }
    }

    @Test
    void fourWorkersRemainBoundedAndTwentyWaitingJobsRejectOverload() throws Exception {
        DiagramQueryService diagrams = mock(DiagramQueryService.class);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        CountDownLatch allWorkersStarted = new CountDownLatch(4);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        when(diagrams.getDiagramById(eq(id), any(Caller.class))).thenAnswer(invocation -> {
            if (Thread.currentThread().getName().startsWith("diagramio-export-")) {
                allWorkersStarted.countDown();
                if (!releaseWorkers.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Worker release timed out");
                }
            }
            return diagram(id, owner, true);
        });

        ExportJobService exports = new ExportJobService(diagrams, 4, 20, 40);
        try {
            java.util.List<UUID> accepted = new java.util.ArrayList<>();
            for (int n = 0; n < 4; n++) {
                accepted.add(exports.submit(id, owner).jobId());
            }
            assertTrue(allWorkersStarted.await(5, TimeUnit.SECONDS),
                    "Four export workers did not start");
            for (int n = 0; n < 20; n++) {
                accepted.add(exports.submit(id, owner).jobId());
            }
            ExportBulkheadStats busy = exports.bulkheadStats();
            assertEquals(4, busy.workerLimit());
            assertEquals(4, busy.activeWorkers());
            assertEquals(20, busy.queueCapacity());
            assertEquals(20, busy.queuedJobs());
            assertEquals(24, busy.retainedJobs());
            ResponseStatusException overloaded = assertThrows(ResponseStatusException.class,
                    () -> exports.submit(id, owner));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, overloaded.getStatusCode());
            assertEquals(1, exports.bulkheadStats().rejectedJobs());
            assertEquals(24, exports.bulkheadStats().retainedJobs());

            releaseWorkers.countDown();
            for (UUID jobId : accepted) {
                assertEquals(ExportJobStatus.SUCCEEDED, awaitTerminal(exports, jobId, owner));
            }
        } finally {
            releaseWorkers.countDown();
            exports.shutdown();
        }
    }

    private static ExportJobStatus awaitTerminal(ExportJobService exports, UUID id, UUID owner)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            ExportJobStatus status = exports.status(id, owner).status();
            if (status == ExportJobStatus.SUCCEEDED || status == ExportJobStatus.FAILED) {
                return status;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for export job " + id);
    }

    private static Diagram diagram(UUID id, UUID owner, boolean valid) {
        Diagram d = new Diagram();
        d.setId(id);
        d.setOwnerId(owner);
        d.setTitle("Bulkhead test");
        d.setDiagramType(DiagramType.ERD);
        d.setVersion(1);
        d.setData(valid ? DiagramData.of("{\"nodes\":[],\"edges\":[]}") : null);
        return d;
    }
}
