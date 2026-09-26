package com.isaralert.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Error paths no controller currently reaches over HTTP (the REST integration tests cover the rest).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("unexpected errors are a 500 that doesn't leak internals")
    void unexpectedError() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleGenericException(new IllegalStateException("password=secret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("message", "An unexpected error occurred");
    }

    @Test
    @DisplayName("scraping errors are a 500 with a generic message")
    void scrapingError() {
        ScrapingException ex = new ScrapingException("WG_GESUCHT", "boom", new RuntimeException());

        ResponseEntity<Map<String, Object>> response = handler.handleScrapingException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("message", "Scraping operation failed");
        assertThat(ex.getMessage()).contains("WG_GESUCHT", "boom");
        assertThat(ex.getSource()).isEqualTo("WG_GESUCHT");
        assertThat(new ScrapingException("WG_GESUCHT", "no cause").getCause()).isNull();
    }
}
