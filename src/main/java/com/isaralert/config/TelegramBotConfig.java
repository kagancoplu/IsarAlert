package com.isaralert.config;

import com.isaralert.service.TelegramBotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Registers the Telegram bot with the TelegramBotsApi.
 *
 * Manual registration is required for Spring Boot 3.x compatibility,
 * since the legacy auto-configuration starters are incompatible.
 *
 * The bot is only registered when {@code telegram.bot.token} is non-blank.
 * ({@code @ConditionalOnProperty} would treat an empty value as "set" and crash on startup.)
 */
@Slf4j
@Configuration
public class TelegramBotConfig {

    @Bean
    @ConditionalOnExpression("!'${telegram.bot.token:}'.isBlank()")
    public TelegramBotsApi telegramBotsApi(TelegramBotService botService) throws TelegramApiException {
        log.info("Registering Telegram bot: @{}", botService.getBotUsername());
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(botService);
        log.info("Telegram bot registered successfully");
        return api;
    }
}
