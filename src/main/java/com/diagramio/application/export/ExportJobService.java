package com.diagramio.application.export;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Single-instance MVP. Jobs/results are intentionally lost on application restart.
 * A bounded executor isolates export work from HTTP request threads.
 */
@Service
public class ExportJobService {
    // Phase 2 WBS: four dedicated workers, separate from HTTP request execution.
    private static final int WORKERS = 4;
    private static final int QUEUE_CAPACITY = 20;
    private static final int MAX_RETAINED_JOBS = 40;
    private static final int MAX_EXPORT_BYTES = 3 * 1024 * 1024;
    private static final Duration RESULT_TTL = Duration.ofMinutes(10);

    private final DiagramService diagramService;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private final ThreadPoolExecutor executor;
    private final int queueCapacity;
    private final int maxRetainedJobs;
    private final AtomicLong rejectedJobs = new AtomicLong();
    private final Counter succeededCounter;
    private final Counter failedCounter;
    private final Timer durationTimer;

    // Preserve the one-argument constructor used by existing non-Spring unit tests.
    public ExportJobService(DiagramService diagramService) {
        this(diagramService, WORKERS, QUEUE_CAPACITY, MAX_RETAINED_JOBS, new SimpleMeterRegistry());
    }

    @Autowired
    public ExportJobService(DiagramService diagramService, MeterRegistry meterRegistry) {
        this(diagramService, WORKERS, QUEUE_CAPACITY, MAX_RETAINED_JOBS, meterRegistry);
    }

    // Package-private: allow small, deterministic bulkhead limits in unit tests.
    ExportJobService(DiagramService diagramService, int workers, int queueCapacity, int maxRetainedJobs) {
        this(diagramService, workers, queueCapacity, maxRetainedJobs, new SimpleMeterRegistry());
    }

    ExportJobService(DiagramService diagramService, int workers, int queueCapacity,
                     int maxRetainedJobs, MeterRegistry meterRegistry) {
        if (workers < 1 || queueCapacity < 1 || maxRetainedJobs < workers + queueCapacity) {
            throw new IllegalArgumentException("Invalid export bulkhead limits");
        }
        this.diagramService = java.util.Objects.requireNonNull(diagramService);
        this.queueCapacity = queueCapacity;
        this.maxRetainedJobs = maxRetainedJobs;
        AtomicInteger nextThread = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                task -> {
                    Thread worker = new Thread(task, "diagramio-export-" + nextThread.incrementAndGet());
                    worker.setDaemon(true);
                    return worker;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        java.util.Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.succeededCounter = Counter.builder("diagramio.export.jobs.succeeded")
                .description("Completed JSON exports that succeeded").register(meterRegistry);
        this.failedCounter = Counter.builder("diagramio.export.jobs.failed")
                .description("Completed JSON exports that failed").register(meterRegistry);
        this.durationTimer = Timer.builder("diagramio.export.duration")
                .description("Background JSON export processing duration")
                .publishPercentileHistogram().register(meterRegistry);

        Gauge.builder("diagramio.export.workers.limit", executor, ThreadPoolExecutor::getMaximumPoolSize)
                .register(meterRegistry);
        Gauge.builder("diagramio.export.workers.active", executor, ThreadPoolExecutor::getActiveCount)
                .register(meterRegistry);
        Gauge.builder("diagramio.export.queue.depth", executor, e -> e.getQueue().size())
                .register(meterRegistry);
        Gauge.builder("diagramio.export.queue.capacity", this, e -> e.queueCapacity)
                .register(meterRegistry);
        Gauge.builder("diagramio.export.jobs.retained", jobs, m -> m.size())
                .register(meterRegistry);
        FunctionCounter.builder("diagramio.export.jobs.rejected", rejectedJobs, AtomicLong::get)
                .description("Export submissions rejected by admission limits")
                .register(meterRegistry);
    }

    public ExportJobView submit(UUID diagramId, UUID ownerId) {
        requireAuthenticated(ownerId);
        requireOwnership(diagramId, ownerId);
        Job job = new Job(UUID.randomUUID(), diagramId, ownerId);
        synchronized (admissionLock) {
            cleanupExpired();
            if (jobs.size() >= maxRetainedJobs) {
                rejectedJobs.incrementAndGet();
                throw overloaded();
            }
            jobs.put(job.id, job);
            try {
                executor.execute(() -> execute(job));
            } catch (RejectedExecutionException e) {
                jobs.remove(job.id, job);
                rejectedJobs.incrementAndGet();
                throw overloaded();
            }
        }
        return job.view();
    }

    /** Thread-safe, process-local bulkhead snapshot for tests and future telemetry. */
    public ExportBulkheadStats bulkheadStats() {
        return new ExportBulkheadStats(
                executor.getMaximumPoolSize(), executor.getActiveCount(),
                executor.getQueue().size(), queueCapacity, rejectedJobs.get(),
                executor.getCompletedTaskCount(), jobs.size());
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
        long started = System.nanoTime();
        job.status = ExportJobStatus.RUNNING;
        boolean succeeded = false;
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
            succeeded = true;
        } catch (Exception e) {
            // Never expose exception details or source JSON to API consumers.
            job.errorCode = "EXPORT_FAILED";
        } finally {
            job.completedAt = Instant.now();
            durationTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            if (succeeded) {
                succeededCounter.increment();
                job.status = ExportJobStatus.SUCCEEDED;
            } else {
                failedCounter.increment();
                job.status = ExportJobStatus.FAILED;
            }
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
