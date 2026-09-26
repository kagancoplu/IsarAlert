package com.isaralert;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test — verifies that the Spring application context loads successfully
 * with a real PostgreSQL database (via Testcontainers).
 *
 * <p>If this test passes, Flyway migrations ran cleanly, all beans wired
 * correctly, and the configuration is valid.</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class IsarAlertApplicationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("isaralert_test")
            .withUsername("isaralert")
            .withPassword("testpassword");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Disable Telegram bot registration in tests — no real token needed
        registry.add("telegram.bot.token", () -> "");
        registry.add("isaralert.scraper.enabled", () -> "false");
    }

    @Test
    void contextLoads() {
        // If the context loads without throwing, the test passes.
    }
}
