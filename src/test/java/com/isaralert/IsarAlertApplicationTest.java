package com.isaralert;

import com.isaralert.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Smoke test — verifies that the Spring application context loads successfully
 * with a real PostgreSQL database (via Testcontainers).
 *
 * <p>If this test passes, Flyway migrations ran cleanly, all beans wired
 * correctly, and the configuration is valid.</p>
 */
class IsarAlertApplicationTest extends IntegrationTestBase {

    @Test
    void contextLoads() {
        // If the context loads without throwing, the test passes.
    }
}
