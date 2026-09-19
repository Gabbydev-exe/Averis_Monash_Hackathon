package com.shipping.api;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseStatusControllerTests {
    @Test
    void reportsMissingConfigurationWithoutClaimingAConnection() {
        var result = new DatabaseStatusController(Optional.empty()).status();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getBody()).containsEntry("status", "not_configured");
    }

    @Test
    void requiresSuccessfulQueryForConnectedStatus() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        var result = new DatabaseStatusController(Optional.of(jdbc)).status();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsEntry("status", "connected");
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void doesNotExposeDriverErrorDetails() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("private connection details"));
        var result = new DatabaseStatusController(Optional.of(jdbc)).status();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getBody()).containsEntry("status", "unavailable");
        assertThat(result.getBody().toString()).doesNotContain("private connection details");
    }

    @Test
    void unexpectedQueryResultIsNotHealthy() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(null);
        var result = new DatabaseStatusController(Optional.of(jdbc)).status();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
