package com.diagramio.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.diagramio.api.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerResponseStatusTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void overloadPreserves429AndErrorResponseSchema() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/diagrams/example/exports");
        ResponseEntity<ErrorResponse> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Export service busy"), request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.timestamp());
        assertEquals(429, body.status());
        assertEquals("Too Many Requests", body.error());
        assertEquals("Export service busy", body.message());
        assertEquals("/api/v1/diagrams/example/exports", body.path());
    }

    @Test
    void otherExplicitStatusesArePreserved() {
        for (HttpStatus status : new HttpStatus[] {
                HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN,
                HttpStatus.NOT_FOUND, HttpStatus.CONFLICT }) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/exports/job");
            ResponseEntity<ErrorResponse> response = handler.handleResponseStatus(
                    new ResponseStatusException(status, "Known export problem"), request);

            assertEquals(status, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(status.value(), response.getBody().status());
            assertEquals("Known export problem", response.getBody().message());
        }
    }
}
