package com.isaralert.config;

import org.springframework.context.annotation.Configuration;

/**
 * Configuration for HTTP client settings used by scrapers.
 *
 * Currently, individual scrapers configure Jsoup connections directly
 * using settings from {@link AppProperties}. This class serves as a
 * central place to add shared HTTP configuration if needed in the future
 * (e.g., proxy settings, custom SSL context, RestTemplate beans).
 */
@Configuration
public class WebClientConfig {

    // Future configuration examples:
    //
    // @Bean
    // public RestTemplate restTemplate() {
    //     return new RestTemplateBuilder()
    //         .setConnectTimeout(Duration.ofSeconds(15))
    //         .setReadTimeout(Duration.ofSeconds(15))
    //         .build();
    // }
}
