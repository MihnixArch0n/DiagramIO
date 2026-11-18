package com.diagramio.application.export;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramService;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Single-instance MVP. Jobs/results are intentionally lost on application restart.
 * Step 9 will extend the executor/bulkhead configuration and monitoring.
 */
@Service
public class ExportJobService {
    private static final int WORKERS = 2;
    private static final int QUEUE_CAPACITY = 20;
    private static final int MAX_RETAINED_JOBS = 40;
    private static final int MAX_EXPORT_BYTES = 3 * 1024 * 1024;
    private static final Duration RESULT_TTL = Duration.ofMinutes(10);

    private final DiagramService diagramService;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private final ThreadPoolExecutor executor;

    public ExportJobService(DiagramService diagramService) {
        this.diagramService = diagramService;
        AtomicInteger nextThread = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                WORKERS,
                WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                task -> {
                    Thread worker = new Thread(task, "diagramio-export-" + nextThread.incrementAndGet());
                    worker.setDaemon(true);
                    return worker;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public ExportJobView submit(UUID diagramId, UUID ownerId) {
        requireAuthenticated(ownerId);
        requireOwnership(diagramId, ownerId);
        Job job = new Job(UUID.randomUUID(), diagramId, ownerId);
        synchronized (admissionLock) {
            cleanupExpired();
            if (jobs.size() >= MAX_RETAINED_JOBS) {
                throw overloaded();
            }
            jobs.put(job.id, job);
            try {
                executor.execute(() -> execute(job));
            } catch (RejectedExecutionException e) {
                jobs.remove(job.id, job);
                throw overloaded();
            }
        }
        return job.view();
    }

    public ExportJobView status(UUID jobId, UUID requesterId) {
        return requireJob(jobId, requesterId).view();
    }

    public byte[] download(UUID jobId, UUID requesterId) {
        Job job = requireJob(jobId, requesterId);
        // Revalidate ownership on download; deletion/ownership change denies access.
        requireOwnership(job.diagramId, requesterId);
        if (job.status != ExportJobStatus.SUCCEEDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Export is not ready");
        }
        return job.output.clone();
    }

    private Job requireJob(UUID jobId, UUID requesterId) {
        requireAuthenticated(requesterId);
        cleanupExpired();
        Job job = jobs.get(jobId);
        if (job == null || !job.ownerId.equals(requesterId)) {
            // Hide existence of another user's job.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Export job not found");
        }
        return job;
    }

    private void requireOwnership(UUID diagramId, UUID ownerId) {
        if (diagramId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "diagramId is required");
        }
        Diagram diagram = diagramService.getDiagramById(diagramId, Caller.user(ownerId));
        if (!diagram.isOwner(ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only diagram owner can export");
        }
    }

    private void execute(Job job) {
        job.status = ExportJobStatus.RUNNING;
        try {
            Diagram diagram = diagramService.getDiagramById(job.diagramId, Caller.user(job.ownerId));
            if (!diagram.isOwner(job.ownerId)) {
                throw new IllegalStateException("Export permission revoked");
            }
            // One database read, then serialize the immutable JSON string from that read.
            var root = jsonMapper.createObjectNode();
            root.put("diagramId", diagram.getId().toString());
            root.put("title", diagram.getTitle());
            root.put("diagramType", diagram.getDiagramType().name());
            root.put("version", diagram.getVersion());
            root.put("exportedAt", Instant.now().toString());
            root.set("data", jsonMapper.readTree(diagram.getData().json()));
            byte[] output = jsonMapper.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
            if (output.length > MAX_EXPORT_BYTES) {
                throw new IllegalStateException("Export exceeds output limit");
            }
            job.output = output;
            job.completedAt = Instant.now();
            job.status = ExportJobStatus.SUCCEEDED;
        } catch (Exception e) {
            // Never expose exception details or source JSON to API consumers.
            job.errorCode = "EXPORT_FAILED";
            job.completedAt = Instant.now();
            job.status = ExportJobStatus.FAILED;
        }
    }

    private void cleanupExpired() {
        Instant cutoff = Instant.now().minus(RESULT_TTL);
        jobs.entrySet().removeIf(entry -> {
            Job job = entry.getValue();
            Instant completedAt = job.completedAt;
            return completedAt != null && completedAt.isBefore(cutoff);
        });
    }

    private static void requireAuthenticated(UUID userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    private static ResponseStatusException overloaded() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Export service busy");
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class Job {
        private final UUID id;
        private final UUID diagramId;
        private final UUID ownerId;
        private final Instant createdAt = Instant.now();
        private volatile ExportJobStatus status = ExportJobStatus.QUEUED;
        private volatile Instant completedAt;
        private volatile String errorCode;
        private volatile byte[] output;

        private Job(UUID id, UUID diagramId, UUID ownerId) {
            this.id = id;
            this.diagramId = diagramId;
            this.ownerId = ownerId;
        }

        private ExportJobView view() {
            return new ExportJobView(id, diagramId, status, createdAt, completedAt, errorCode);
        }
    }
}
