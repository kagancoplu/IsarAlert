package com.isaralert.event;

import org.springframework.context.ApplicationEvent;

/**
 * Published when a user requests a manual scan via the Telegram bot (/forcescan).
 * SchedulerService listens for this event and runs a scan cycle.
 *
 * This decouples TelegramBotService from SchedulerService, breaking the
 * circular dependency: TelegramBotService → SchedulerService → NotificationService
 * → TelegramMessageSender → TelegramBotService.
 */
public class ScanRequestedEvent extends ApplicationEvent {

    private final Long requestedByChatId;

    public ScanRequestedEvent(Object source, Long requestedByChatId) {
        super(source);
        this.requestedByChatId = requestedByChatId;
    }

    public Long getRequestedByChatId() {
        return requestedByChatId;
    }
}
