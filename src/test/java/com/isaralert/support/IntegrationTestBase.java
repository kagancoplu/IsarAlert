package com.isaralert.support;

import com.isaralert.service.SchedulerService;
import com.isaralert.service.TelegramBotService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Base class for integration tests: the whole Spring application against a real PostgreSQL
 * (Testcontainers), with Telegram and WG-Gesucht replaced by local fake servers.
 *
 * <p>The database container, the fakes and the Spring context are started once and shared by
 * all subclasses. Every test starts with empty tables and reset fakes; use {@link #newChatId()}
 * for a fresh Telegram user, since the bot keeps wizard/cooldown state in memory per chat.</p>
 *
 * <p>The scheduler's cron is disabled — tests trigger scan cycles themselves.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    // Started once per JVM and shared; Testcontainers' Ryuk container removes it afterwards.
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("isaralert_it")
            .withUsername("isaralert")
            .withPassword("test");
    protected static final FakeTelegramApi TELEGRAM = new FakeTelegramApi();
    protected static final FakeWgGesucht WG_GESUCHT = new FakeWgGesucht();

    private static final AtomicLong CHAT_IDS = new AtomicLong(100_000);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Empty token: the bot bean exists and can send, but isn't registered for long polling
        registry.add("telegram.bot.token", () -> "");
        registry.add("telegram.bot.api-url", TELEGRAM::baseUrl);

        registry.add("isaralert.scraper.enabled", () -> "true");
        registry.add("isaralert.scraper.cron", () -> "-");          // "-" disables the schedule
        registry.add("isaralert.scraper.request-delay-ms", () -> "0");
        registry.add("isaralert.scraper.wg-gesucht-base-url", WG_GESUCHT::baseUrl);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TelegramBotService bot;

    @Autowired
    protected SchedulerService scheduler;

    @BeforeEach
    void resetState() {
        // A /forcescan from a previous test may still be running asynchronously
        await().atMost(Duration.ofSeconds(30)).until(() -> !scheduler.isScanInProgress());
        jdbc.execute("TRUNCATE notifications, listings, search_criteria, users RESTART IDENTITY CASCADE");
        TELEGRAM.reset();
        WG_GESUCHT.reset();
    }

    /** A chat ID no other test has used. */
    protected static long newChatId() {
        return CHAT_IDS.incrementAndGet();
    }

    /** Simulates the user in {@code chatId} sending {@code text} to the bot. */
    protected void userSends(long chatId, String text) {
        userSends(chatId, "Kagan", text);
    }

    /** Simulates a user with the given first name sending {@code text} to the bot. */
    protected void userSends(long chatId, String firstName, String text) {
        User from = new User(chatId, firstName, false);
        from.setUserName("user" + chatId);

        Message message = new Message();
        message.setMessageId(1);
        message.setChat(new Chat(chatId, "private"));
        message.setFrom(from);
        message.setText(text);

        Update update = new Update();
        update.setMessage(message);
        bot.onUpdateReceived(update);
    }

    /** Asserts that Telegram accepted every message (none failed MarkdownV2 or length checks). */
    protected static void assertNoRejectedMessages() {
        assertThat(TELEGRAM.rejected())
                .as("messages Telegram would reject with 400 Bad Request")
                .isEmpty();
    }
}
