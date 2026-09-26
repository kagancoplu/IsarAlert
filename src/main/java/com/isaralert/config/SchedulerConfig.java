package com.isaralert.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduled task execution infrastructure.
 * The actual scheduled tasks are defined in {@link com.isaralert.service.SchedulerService}.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulerConfig {
    // Configuration is handled via annotations.
    // Custom thread pool can be added here if needed:
    //
    // @Bean
    // public TaskScheduler taskScheduler() {
    //     ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    //     scheduler.setPoolSize(2);
    //     scheduler.setThreadNamePrefix("isaralert-scheduler-");
    //     return scheduler;
    // }
}
