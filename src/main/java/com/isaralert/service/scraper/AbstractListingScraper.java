package com.isaralert.service.scraper;

import com.isaralert.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base class for all scrapers providing shared utilities:
 * <ul>
 *     <li>Rate limiting between requests</li>
 *     <li>User-Agent configuration</li>
 *     <li>Jsoup connection setup</li>
 *     <li>Common parsing helpers</li>
 * </ul>
 *
 * <p>Subclasses should call {@link #createConnection(String)} to get a
 * pre-configured Jsoup connection, and {@link #respectRateLimit()} between
 * consecutive HTTP requests.</p>
 */
@Slf4j
public abstract class AbstractListingScraper implements ListingScraper {

    protected final AppProperties appProperties;
    private final Random random = new Random();

    // Common user agents to rotate
    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
    );

    protected AbstractListingScraper(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * Creates a pre-configured Jsoup connection with realistic browser headers.
     */
    protected Connection createConnection(String url) {
        return Jsoup.connect(url)
                .userAgent(getRandomUserAgent())
                .timeout(appProperties.getScraper().getTimeoutMs())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .followRedirects(true);
    }

    /**
     * Pauses execution to respect rate limits and avoid being flagged as a bot.
     * Adds a small random jitter to make the delay less predictable.
     */
    protected void respectRateLimit() {
        try {
            long baseDelay = appProperties.getScraper().getRequestDelayMs();
            // 0 to 50% of base delay; nextLong() needs a positive bound, so tiny delays get no jitter
            long jitter = baseDelay >= 2 ? random.nextLong(baseDelay / 2) : 0;
            long totalDelay = baseDelay + jitter;
            if (totalDelay <= 0) return;
            log.debug("Rate limiting: sleeping {}ms", totalDelay);
            Thread.sleep(totalDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Rate limit sleep interrupted");
        }
    }

    /**
     * Returns a random User-Agent string to reduce detection.
     */
    protected String getRandomUserAgent() {
        return USER_AGENTS.get(random.nextInt(USER_AGENTS.size()));
    }

    // ==================== Parsing Utilities ====================

    /**
     * Extracts a decimal number from a text string.
     * E.g., "1.200 €" → 1200.00, "3,5 Zimmer" → 3.5
     */
    protected BigDecimal parsePrice(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            // Remove currency symbols, whitespace, and normalize separators
            String cleaned = text
                    .replaceAll("[€$\\s]", "")
                    .replace(".", "")       // Remove thousands separator (German format)
                    .replace(",", ".");     // Convert decimal comma to period
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            log.debug("Could not parse price from: '{}'", text);
            return null;
        }
    }

    /**
     * Extracts room count from text like "3 Zimmer" or "2,5 Zi.".
     */
    protected BigDecimal parseRooms(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            Matcher matcher = Pattern.compile("(\\d+[.,]?\\d*)").matcher(text);
            if (matcher.find()) {
                String rooms = matcher.group(1).replace(",", ".");
                return new BigDecimal(rooms);
            }
        } catch (NumberFormatException e) {
            log.debug("Could not parse rooms from: '{}'", text);
        }
        return null;
    }

    /**
     * Extracts square meters from text like "65 m²" or "65m2".
     */
    protected Integer parseSizeSqm(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            Matcher matcher = Pattern.compile("(\\d+)").matcher(text);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (NumberFormatException e) {
            log.debug("Could not parse size from: '{}'", text);
        }
        return null;
    }
}
