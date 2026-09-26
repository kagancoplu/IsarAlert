package com.isaralert.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configures the OpenAPI 3 / Swagger UI documentation.
 *
 * <p>Access the UI at: <a href="http://localhost:8080/swagger-ui.html">http://localhost:8080/swagger-ui.html</a></p>
 * <p>Raw JSON spec: <a href="http://localhost:8080/v3/api-docs">http://localhost:8080/v3/api-docs</a></p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI isarAlertOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("IsarAlert API")
                        .description("""
                                Munich Apartment Notifier — REST API for managing users, \
                                search criteria, and viewing scraped listings.
                                
                                **Quick start:**
                                1. Register a user via the Telegram bot (`/start`)
                                2. Look up your user ID: `GET /api/users/by-chat/{chatId}`
                                3. Create search criteria: `POST /api/criteria`
                                4. Watch the Telegram notifications roll in!
                                """)
                        .version("0.1.0")
                        .contact(new Contact()
                                .name("IsarAlert")
                                .url("https://github.com/your-repo/IsarAlert"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local development server")
                ));
    }
}
