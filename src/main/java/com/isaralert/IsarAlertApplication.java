package com.isaralert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * IsarAlert — Munich Apartment Notifier
 *
 * Monitors housing platforms for new apartment listings matching user criteria
 * and sends real-time notifications via Telegram.
 */
@SpringBootApplication
public class IsarAlertApplication {

    public static void main(String[] args) {
        SpringApplication.run(IsarAlertApplication.class, args);
    }
}
