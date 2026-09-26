package com.isaralert.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Type-safe binding for custom application properties under the "isaralert" prefix.
 *
 * <pre>
 * isaralert:
 *   scraper:
 *     enabled: true
 *     cron: "0 *&#47;15 * * * *"
 *     request-delay-ms: 3000
 *     user-agent: "..."
 *     timeout-ms: 15000
 * </pre>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "isaralert")
public class AppProperties {

    private Scraper scraper = new Scraper();

    @Getter
    @Setter
    public static class Scraper {
        private boolean enabled = true;
        private String cron = "0 */15 * * * *";
        private long requestDelayMs = 3000;
        private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
        private int timeoutMs = 15000;
    }
}
