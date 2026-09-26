package com.isaralert.service;

import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Abstraction over the Telegram message delivery mechanism.
 *
 * <p>Allows {@link NotificationService} to be tested without requiring
 * a real {@link TelegramBotService} (which extends a concrete Telegram SDK class
 * that is difficult to mock with standard Mockito byte-buddy mocking).</p>
 */
public interface TelegramMessageSender {

    /**
     * Sends a text message to the given Telegram chat.
     *
     * @param chatId the recipient's Telegram chat ID
     * @param text   the message text (Markdown supported)
     * @throws TelegramApiException if the Telegram API call fails
     */
    void sendMessage(Long chatId, String text) throws TelegramApiException;
}
