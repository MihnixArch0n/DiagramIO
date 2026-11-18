package com.diagramio.application.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramService;
import com.diagramio.domain.diagram.DiagramType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ExportJobServiceTest {
    private final DiagramService diagrams = mock(DiagramService.class);
    private final ExportJobService exports = new ExportJobService(diagrams);

    @AfterEach
    void shutdown() {
        exports.shutdown();
    }

    @Test
    void ownerCanSubmitPollAndDownloadJson() throws InterruptedException {
        UUID diagramId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(diagrams.getDiagramById(eq(diagramId), any(Caller.class)))
                .thenReturn(diagram(diagramId, owner));

        ExportJobView submitted = exports.submit(diagramId, owner);
        assertEquals(diagramId, submitted.diagramId());
        ExportJobView finished = awaitTerminal(submitted.jobId(), owner);
        assertEquals(ExportJobStatus.SUCCEEDED, finished.status());
        String json = new String(exports.download(submitted.jobId(), owner), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"diagramId\":\"" + diagramId + "\""));
        assertTrue(json.contains("\"version\":3"));
        assertTrue(json.contains("\"nodes\":[]"));
    }

    @Test
    void cannotReadAnotherUsersJob() throws InterruptedException {
        UUID diagramId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        when(diagrams.getDiagramById(eq(diagramId), any(Caller.class)))
                .thenReturn(diagram(diagramId, owner));

        UUID jobId = exports.submit(diagramId, owner).jobId();
        awaitTerminal(jobId, owner);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> exports.status(jobId, stranger)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> exports.download(jobId, stranger)).getStatusCode());
    }

    @Test
    void aDifferentUserCannotSubmitExport() {
        UUID diagramId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(diagrams.getDiagramById(eq(diagramId), any(Caller.class)))
                .thenReturn(diagram(diagramId, owner));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> exports.submit(diagramId, UUID.randomUUID())).getStatusCode());
    }

    @Test
    void workerFailureBecomesFailedStatus() throws InterruptedException {
        UUID diagramId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Diagram broken = diagram(diagramId, owner);
        broken.setData(null);
        when(diagrams.getDiagramById(eq(diagramId), any(Caller.class)))
                .thenReturn(broken);

        UUID jobId = exports.submit(diagramId, owner).jobId();
        ExportJobView finished = awaitTerminal(jobId, owner);
        assertEquals(ExportJobStatus.FAILED, finished.status());
        assertEquals("EXPORT_FAILED", finished.errorCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> exports.download(jobId, owner)).getStatusCode());
    }

    private ExportJobView awaitTerminal(UUID jobId, UUID owner) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            ExportJobView job = exports.status(jobId, owner);
            if (job.status() == ExportJobStatus.SUCCEEDED || job.status() == ExportJobStatus.FAILED) {
                return job;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Export job did not complete in 5 seconds");
    }

    private Diagram diagram(UUID diagramId, UUID owner) {
        Diagram diagram = new Diagram();
        diagram.setId(diagramId);
        diagram.setOwnerId(owner);
        diagram.setTitle("Async export fixture");
        diagram.setDiagramType(DiagramType.ERD);
        diagram.setVersion(3);
        diagram.setData(DiagramData.of("{\"nodes\":[],\"edges\":[]}"));
        return diagram;
    }
}
