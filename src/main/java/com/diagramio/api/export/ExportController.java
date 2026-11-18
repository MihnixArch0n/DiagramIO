package com.diagramio.api.export;

import com.diagramio.api.CurrentUser;
import com.diagramio.application.export.ExportJobService;
import com.diagramio.application.export.ExportJobView;
import com.diagramio.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ExportController {
    private final ExportJobService exportJobService;

    public ExportController(ExportJobService exportJobService) {
        this.exportJobService = exportJobService;
    }

    @PostMapping({"/api/v1/diagrams/{id}/exports", "/api/v1/diagrams/{id}/export"})
    @Operation(summary = "Create an asynchronous JSON export job (owner only)")
    public ResponseEntity<ExportJobView> create(
            @PathVariable UUID id,
            @CurrentUser UUID ownerId,
            HttpServletRequest request
    ) {
        ExportJobView job = exportJobService.submit(id, ownerId);
        // Keep old URLs stable; the WBS alias points clients to /api/v1/jobs/{jobId}.
        String statusPath = request.getRequestURI().endsWith("/export")
                ? "/api/v1/jobs/" : "/api/v1/exports/";
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(statusPath)
                .path(job.jobId().toString())
                .build().toUri();
        return ResponseEntity.accepted().location(location).body(job);
    }

    @GetMapping({"/api/v1/exports/{jobId}", "/api/v1/jobs/{jobId}"})
    @Operation(summary = "Get export status (creator only)")
    public ExportJobView status(@PathVariable UUID jobId, @CurrentUser UUID ownerId) {
        return exportJobService.status(jobId, ownerId);
    }

    @GetMapping({"/api/v1/exports/{jobId}/download", "/api/v1/jobs/{jobId}/download"})
    @Operation(summary = "Download completed JSON export (creator only)")
    public ResponseEntity<byte[]> download(@PathVariable UUID jobId, @CurrentUser UUID ownerId) {
        byte[] payload = exportJobService.download(jobId, ownerId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("diagram-export-" + jobId + ".json").build().toString())
                .body(payload);
    }
}
