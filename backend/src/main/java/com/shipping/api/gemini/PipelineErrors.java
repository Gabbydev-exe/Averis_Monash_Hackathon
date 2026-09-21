package com.shipping.api.gemini;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = GeminiPipelineController.class)
public class PipelineErrors {
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> databaseFailure() {
        return ResponseEntity.status(503).body(Map.of("message", "Database unavailable. Processing was not saved; please retry."));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> invalid(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", java.util.Objects.requireNonNullElse(error.getReason(), "Request failed.")));
    }
}
