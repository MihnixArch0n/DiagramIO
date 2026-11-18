package com.diagramio.api.export;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramio.api.CurrentUser;
import com.diagramio.application.export.ExportJobService;
import com.diagramio.application.export.ExportJobStatus;
import com.diagramio.application.export.ExportJobView;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExportControllerContractTest {
    private final UUID ownerId = UUID.randomUUID();
    private final UUID diagramId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final ExportJobService service = mock(ExportJobService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ExportController(service))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(CurrentUser.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                        return ownerId;
                    }
                }).build();
    }

    @Test
    void wbsCreateAliasReturns202AndJobLocation() throws Exception {
        when(service.submit(diagramId, ownerId)).thenReturn(job());
        mvc.perform(post("/api/v1/diagrams/{id}/export", diagramId))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", endsWith("/api/v1/jobs/" + jobId)))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void legacyCreateRemainsCompatible() throws Exception {
        when(service.submit(diagramId, ownerId)).thenReturn(job());
        mvc.perform(post("/api/v1/diagrams/{id}/exports", diagramId))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", endsWith("/api/v1/exports/" + jobId)))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void newAndLegacyPollingUrlsUseTheSameService() throws Exception {
        when(service.status(jobId, ownerId)).thenReturn(job());
        mvc.perform(get("/api/v1/jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(get("/api/v1/exports/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void newAndLegacyDownloadUrlsUseTheSameService() throws Exception {
        byte[] result = "{\"nodes\":[],\"edges\":[]}".getBytes(StandardCharsets.UTF_8);
        when(service.download(jobId, ownerId)).thenReturn(result);
        mvc.perform(get("/api/v1/jobs/{jobId}/download", jobId))
                .andExpect(status().isOk())
                .andExpect(content().bytes(result));
        mvc.perform(get("/api/v1/exports/{jobId}/download", jobId))
                .andExpect(status().isOk())
                .andExpect(content().bytes(result));
    }

    private ExportJobView job() {
        return new ExportJobView(jobId, diagramId, ExportJobStatus.QUEUED,
                Instant.parse("2026-11-18T00:00:00Z"), null, null);
    }
}
