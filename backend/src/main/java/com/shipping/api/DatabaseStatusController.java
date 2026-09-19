package com.shipping.api;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatabaseStatusController {
    private static final Logger log = LoggerFactory.getLogger(DatabaseStatusController.class);
    private final Optional<JdbcTemplate> jdbc;

    public DatabaseStatusController(Optional<JdbcTemplate> jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/database/status")
    public ResponseEntity<Map<String, String>> status() {
        if (jdbc.isEmpty()) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, "not_configured");
        }
        try {
            Integer result = jdbc.get().queryForObject("SELECT 1", Integer.class);
            if (Integer.valueOf(1).equals(result)) {
                return response(HttpStatus.OK, "connected");
            }
        } catch (DataAccessException exception) {
            // Log the category only: do not publish connection details or credentials.
            log.warn("Database connection check failed ({})", exception.getClass().getSimpleName());
        }
        return response(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
    }

    private ResponseEntity<Map<String, String>> response(HttpStatus code, String status) {
        return ResponseEntity.status(code)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("database", "mysql", "status", status));
    }
}
