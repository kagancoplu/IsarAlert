package com.isaralert.exception;

/**
 * Thrown when a scraping operation fails (network error, parsing error, blocked, etc.).
 */
public class ScrapingException extends RuntimeException {

    private final String source;

    public ScrapingException(String source, String message) {
        super(String.format("[%s] Scraping failed: %s", source, message));
        this.source = source;
    }

    public ScrapingException(String source, String message, Throwable cause) {
        super(String.format("[%s] Scraping failed: %s", source, message), cause);
        this.source = source;
    }

    public String getSource() {
        return source;
    }
}
