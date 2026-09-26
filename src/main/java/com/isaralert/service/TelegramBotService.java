package com.isaralert.service;

import com.isaralert.dto.SearchCriteriaRequest;
import com.isaralert.event.ScanRequestedEvent;
import com.isaralert.model.User;
import com.isaralert.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Telegram bot that handles user commands and sends notification messages.
 *
 * <h3>Supported Commands</h3>
 * <ul>
 *     <li>{@code /start}       — Register with IsarAlert</li>
 *     <li>{@code /setcriteria} — Interactive wizard to set search filters</li>
 *     <li>{@code /search}      — View your current search criteria</li>
 *     <li>{@code /cancel}      — Cancel ongoing wizard</li>
 *     <li>{@code /stop}        — Pause notifications</li>
 *     <li>{@code /help}        — List available commands</li>
 * </ul>
 */
@Slf4j
@Service
public class TelegramBotService extends TelegramLongPollingBot implements TelegramMessageSender {

    private final String botUsername;
    private final UserRepository userRepository;
    private final SearchCriteriaService searchCriteriaService;
    private final ApplicationEventPublisher eventPublisher;

    /** Telegram rejects longer messages; stay a little below its 4096-character limit. */
    private static final int MAX_MESSAGE_LENGTH = 4000;

    /** District lists longer than this are shortened in replies. */
    private static final int MAX_DISTRICTS_DISPLAY_LENGTH = 300;

    /** In-memory conversation sessions keyed by Telegram chat ID. */
    private final Map<Long, CriteriaSession> sessions = new ConcurrentHashMap<>();

    /** Minimum time between two /forcescan requests from the same chat. */
    private static final Duration FORCE_SCAN_COOLDOWN = Duration.ofMinutes(10);

    /** Last /forcescan time per chat ID, used to enforce {@link #FORCE_SCAN_COOLDOWN}. */
    private final Map<Long, Instant> lastForceScan = new ConcurrentHashMap<>();

    public TelegramBotService(
            @Value("${telegram.bot.token:}") String botToken,
            @Value("${telegram.bot.username:IsarAlertBot}") String botUsername,
            @Value("${telegram.bot.api-url:https://api.telegram.org/bot}") String apiUrl,
            UserRepository userRepository,
            SearchCriteriaService searchCriteriaService,
            ApplicationEventPublisher eventPublisher) {
        super(botOptions(apiUrl), botToken);
        this.botUsername = botUsername;
        this.userRepository = userRepository;
        this.searchCriteriaService = searchCriteriaService;
        this.eventPublisher = eventPublisher;
    }

    /** Bot options pointing at the given Telegram API base URL (overridable so tests can use a fake server). */
    private static DefaultBotOptions botOptions(String apiUrl) {
        DefaultBotOptions options = new DefaultBotOptions();
        options.setBaseUrl(apiUrl);
        return options;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    // ==================== Update Routing ====================

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        String text    = update.getMessage().getText().trim();
        Long   chatId  = update.getMessage().getChatId();
        var    from    = update.getMessage().getFrom();
        String firstName = from != null ? from.getFirstName() : null;
        String username  = from != null ? from.getUserName() : null;

        log.debug("Message from {} (chatId={}): {}", username, chatId, text);

        try {
            // In group chats Telegram sends commands as "/start@IsarAlertBot" — strip the bot name
            String command = text.split("\\s+")[0].toLowerCase().replaceFirst("@.*$", "");

            if (command.startsWith("/")) {
                // Always handle slash commands immediately, even during a session
                switch (command) {
                    case "/start"       -> handleStart(chatId, firstName, username);
                    case "/setcriteria" -> handleSetCriteria(chatId);
                    case "/search"      -> handleSearch(chatId);
                    case "/stop"        -> handleStop(chatId);
                    case "/cancel"      -> handleCancel(chatId);
                    case "/forcescan"   -> handleForceScan(chatId);
                    case "/help"        -> handleHelp(chatId);
                    default             -> sendMessage(chatId, "❓ Unknown command\\. Type /help to see available commands\\.");
                }
            } else if (sessions.containsKey(chatId)) {
                // Non-command text while a wizard session is active
                handleCriteriaStep(chatId, text);
            } else {
                sendMessage(chatId, "❓ I didn't understand that\\. Type /help to see available commands\\.");
            }
        } catch (Exception e) {
            log.error("Error handling message from chatId {}: {}", chatId, e.getMessage());
            try { sendMessage(chatId, "⚠️ Something went wrong\\. Please try again\\."); }
            catch (TelegramApiException ex) { log.error("Failed to send error message", ex); }
        }
    }

    // ==================== Command Handlers ====================

    private void handleStart(Long chatId, String firstName, String username) throws TelegramApiException {
        User user = userRepository.findByTelegramChatId(chatId).orElseGet(() -> {
            User newUser = User.builder()
                    .telegramChatId(chatId)
                    .firstName(firstName)
                    .username(username)
                    .active(true)
                    .build();
            userRepository.save(newUser);
            log.info("New user registered: {} (chatId={})", username, chatId);
            return newUser;
        });

        if (!user.getActive()) {
            user.setActive(true);
            userRepository.save(user);
        }

        String name = firstName != null ? firstName : "there";
        sendMessage(chatId, String.format("""
                🏔️ *Welcome to IsarAlert, %s\\!*

                I monitor WG\\-Gesucht and alert you the moment a matching apartment appears in Munich\\.

                *Get started:*
                1️⃣ Use /setcriteria to tell me what you're looking for
                2️⃣ I'll scan new listings every few minutes
                3️⃣ You'll get a message here as soon as I find a match\\!

                Type /help to see all commands\\.
                """, escape(name)));
    }

    private void handleSetCriteria(Long chatId) throws TelegramApiException {
        if (userRepository.findByTelegramChatId(chatId).isEmpty()) {
            sendMessage(chatId, "❌ Please send /start first to register\\.");
            return;
        }
        sessions.put(chatId, new CriteriaSession());
        sendMessage(chatId, """
                🔍 *Let's set up your apartment search\\!*

                I'll ask you a few questions\\. Type *skip* to leave any field empty\\.
                Type /cancel at any time to abort\\.

                💰 *Step 1/6 — Maximum monthly rent \\(€\\):*
                _Example: 1200_
                """);
    }

    private void handleCancel(Long chatId) throws TelegramApiException {
        sessions.remove(chatId);
        sendMessage(chatId, "❌ Wizard cancelled\\. Type /setcriteria to start again\\.");
    }

    private void handleSearch(Long chatId) throws TelegramApiException {
        User user = userRepository.findByTelegramChatId(chatId).orElse(null);
        if (user == null) {
            sendMessage(chatId, "❌ You're not registered yet\\. Send /start first\\.");
            return;
        }

        // Use the service (runs in @Transactional) instead of user.getSearchCriteria()
        // which is lazy-loaded and throws LazyInitializationException outside a session.
        var criteriaList = searchCriteriaService.findByUserId(user.getId());

        if (criteriaList.isEmpty()) {
            sendMessage(chatId, "📋 No search criteria yet\\. Use /setcriteria to create one\\.");
            return;
        }

        // Many criteria can exceed Telegram's message limit, so pack them into as many messages as needed
        StringBuilder sb = new StringBuilder("📋 *Your Search Criteria:*\n\n");
        for (int i = 0; i < criteriaList.size(); i++) {
            var c = criteriaList.get(i);
            StringBuilder block = new StringBuilder();
            if (criteriaList.size() > 1) {
                block.append("*Criteria ").append(i + 1).append(":*\n");
            }
            block.append("💰 Max rent: ")
              .append(c.getMaxRent() != null ? "€" + escape(euros(c.getMaxRent())) : "any").append("\n");
            block.append("🚪 Rooms: ")
              .append(c.getMinRooms() != null ? escape(c.getMinRooms().stripTrailingZeros().toPlainString()) : "any")
              .append(" \\- ")
              .append(c.getMaxRooms() != null ? escape(c.getMaxRooms().stripTrailingZeros().toPlainString()) : "any").append("\n");
            block.append("📐 Size: ")
              .append(c.getMinSizeSqm() != null ? c.getMinSizeSqm() + " m²" : "any")
              .append(" \\- ")
              .append(c.getMaxSizeSqm() != null ? c.getMaxSizeSqm() + " m²" : "any").append("\n");
            if (c.getDistricts() != null && !c.getDistricts().isEmpty()) {
                block.append("🏘️ Districts: ").append(formatDistricts(c.getDistricts())).append("\n");
            }
            block.append(c.getActive() ? "✅ Active" : "⏸️ Paused").append("\n\n");

            if (sb.length() + block.length() > MAX_MESSAGE_LENGTH) {
                sendMessage(chatId, sb.toString());
                sb.setLength(0);
            }
            sb.append(block);
        }
        sendMessage(chatId, sb.toString());
    }

    private void handleStop(Long chatId) throws TelegramApiException {
        userRepository.findByTelegramChatId(chatId).ifPresentOrElse(
                user -> {
                    user.setActive(false);
                    userRepository.save(user);
                    try { sendMessage(chatId, "⏸️ Notifications paused\\. Send /start to resume\\."); }
                    catch (TelegramApiException e) { log.error("Failed to send stop confirmation", e); }
                },
                () -> {
                    try { sendMessage(chatId, "❌ You're not registered\\. Send /start first\\."); }
                    catch (TelegramApiException e) { log.error("Failed to send message", e); }
                }
        );
    }

    private void handleForceScan(Long chatId) throws TelegramApiException {
        if (userRepository.findByTelegramChatId(chatId).isEmpty()) {
            sendMessage(chatId, "❌ Please send /start first to register\\.");
            return;
        }

        Instant now = Instant.now();
        Instant last = lastForceScan.get(chatId);
        if (last != null && last.plus(FORCE_SCAN_COOLDOWN).isAfter(now)) {
            long minutesLeft = Duration.between(now, last.plus(FORCE_SCAN_COOLDOWN)).toMinutes() + 1;
            sendMessage(chatId, "⏳ Please wait " + minutesLeft + " more minute\\(s\\) before forcing another scan\\.");
            return;
        }
        lastForceScan.put(chatId, now);

        sendMessage(chatId, "🔍 Starting manual scan now\\. I'll notify you if I find anything matching your criteria\\.");
        // Publish event — SchedulerService listens for it asynchronously.
        // This avoids a circular dependency between TelegramBotService and SchedulerService.
        eventPublisher.publishEvent(new ScanRequestedEvent(this, chatId));
    }

    private void handleHelp(Long chatId) throws TelegramApiException {
        sendMessage(chatId, """
                🏔️ *IsarAlert Commands:*

                /start         — Register & activate notifications
                /setcriteria   — Set up your apartment search filters
                /search        — View your current search criteria
                /forcescan     — Trigger an immediate scan now
                /cancel        — Cancel the setup wizard
                /stop          — Pause notifications
                /help          — Show this message
                """);
    }

    // ==================== Criteria Wizard ====================

    /**
     * Routes the user's plain-text reply to the correct wizard step.
     */
    private void handleCriteriaStep(Long chatId, String text) throws TelegramApiException {
        CriteriaSession session = sessions.get(chatId);
        if (session == null) return;

        boolean skip = text.equalsIgnoreCase("skip");

        switch (session.step) {

            case MAX_RENT -> {
                if (!skip) {
                    BigDecimal value = parseBigDecimal(text);
                    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
                        sendMessage(chatId, "❗ Please enter a valid amount \\(e\\.g\\. *1200*\\) or type *skip*\\.");
                        return;
                    }
                    session.maxRent = value;
                }
                session.step = CriteriaSession.Step.MIN_ROOMS;
                sendMessage(chatId, """
                        🚪 *Step 2/6 — Minimum number of rooms:*
                        _Example: 2_
                        """);
            }

            case MIN_ROOMS -> {
                if (!skip) {
                    BigDecimal value = parseBigDecimal(text);
                    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
                        sendMessage(chatId, "❗ Please enter a valid room count \\(e\\.g\\. *2*\\) or type *skip*\\.");
                        return;
                    }
                    session.minRooms = value;
                }
                session.step = CriteriaSession.Step.MAX_ROOMS;
                sendMessage(chatId, """
                        🚪 *Step 3/6 — Maximum number of rooms:*
                        _Example: 4_
                        """);
            }

            case MAX_ROOMS -> {
                if (!skip) {
                    BigDecimal value = parseBigDecimal(text);
                    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
                        sendMessage(chatId, "❗ Please enter a valid room count \\(e\\.g\\. *4*\\) or type *skip*\\.");
                        return;
                    }
                    if (session.minRooms != null && value.compareTo(session.minRooms) < 0) {
                        sendMessage(chatId, "❗ Maximum rooms can't be less than your minimum \\("
                                + escape(session.minRooms.stripTrailingZeros().toPlainString()) + "\\)\\. Try again or type *skip*\\.");
                        return;
                    }
                    session.maxRooms = value;
                }
                session.step = CriteriaSession.Step.MIN_SIZE;
                sendMessage(chatId, """
                        📐 *Step 4/6 — Minimum size \\(m²\\):*
                        _Example: 40_
                        """);
            }

            case MIN_SIZE -> {
                if (!skip) {
                    Integer value = parseInteger(text);
                    if (value == null || value <= 0) {
                        sendMessage(chatId, "❗ Please enter a valid size in m² \\(e\\.g\\. *40*\\) or type *skip*\\.");
                        return;
                    }
                    session.minSizeSqm = value;
                }
                session.step = CriteriaSession.Step.MAX_SIZE;
                sendMessage(chatId, """
                        📐 *Step 5/6 — Maximum size \\(m²\\):*
                        _Example: 90_
                        """);
            }

            case MAX_SIZE -> {
                if (!skip) {
                    Integer value = parseInteger(text);
                    if (value == null || value <= 0) {
                        sendMessage(chatId, "❗ Please enter a valid size in m² \\(e\\.g\\. *90*\\) or type *skip*\\.");
                        return;
                    }
                    if (session.minSizeSqm != null && value < session.minSizeSqm) {
                        sendMessage(chatId, "❗ Maximum size can't be less than your minimum \\("
                                + session.minSizeSqm + " m²\\)\\. Try again or type *skip*\\.");
                        return;
                    }
                    session.maxSizeSqm = value;
                }
                session.step = CriteriaSession.Step.DISTRICTS;
                sendMessage(chatId, """
                        🏘️ *Step 6/6 — Preferred districts \\(comma\\-separated\\):*
                        _Example: Maxvorstadt, Schwabing, Haidhausen_

                        Or type *skip* to accept listings from any district\\.
                        """);
            }

            case DISTRICTS -> {
                if (!skip) {
                    session.districts = Arrays.stream(text.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                }
                // Save and finish
                saveCriteriaAndFinish(chatId, session);
            }
        }
    }

    private void saveCriteriaAndFinish(Long chatId, CriteriaSession session) throws TelegramApiException {
        try {
            SearchCriteriaRequest request = SearchCriteriaRequest.builder()
                    .telegramChatId(chatId)
                    .maxRent(session.maxRent)
                    .minRooms(session.minRooms)
                    .maxRooms(session.maxRooms)
                    .minSizeSqm(session.minSizeSqm)
                    .maxSizeSqm(session.maxSizeSqm)
                    .districts(session.districts)
                    .build();

            searchCriteriaService.create(request);
            sessions.remove(chatId);

            log.info("Search criteria saved via Telegram for chatId={}", chatId);

            sendMessage(chatId, buildSummary(session));

        } catch (Exception e) {
            sessions.remove(chatId);
            log.error("Failed to save criteria for chatId={}: {}", chatId, e.getMessage());
            sendMessage(chatId, "⚠️ Failed to save criteria\\. Please try /setcriteria again\\.");
        }
    }

    private String buildSummary(CriteriaSession s) {
        StringBuilder sb = new StringBuilder("✅ *Search criteria saved\\!*\n\n");
        sb.append("💰 Max rent: ")
          .append(s.maxRent != null ? "€" + escape(euros(s.maxRent)) : "any").append("\n");
        sb.append("🚪 Rooms: ")
          .append(s.minRooms != null ? escape(s.minRooms.stripTrailingZeros().toPlainString()) : "any")
          .append(" \\- ")
          .append(s.maxRooms != null ? escape(s.maxRooms.stripTrailingZeros().toPlainString()) : "any").append("\n");
        sb.append("📐 Size: ")
          .append(s.minSizeSqm != null ? s.minSizeSqm + " m²" : "any")
          .append(" \\- ")
          .append(s.maxSizeSqm != null ? s.maxSizeSqm + " m²" : "any").append("\n");
        if (s.districts != null && !s.districts.isEmpty()) {
            sb.append("🏘️ Districts: ").append(formatDistricts(s.districts)).append("\n");
        }
        sb.append("\n🔍 I'll keep scanning WG\\-Gesucht and notify you instantly when I find a match\\!");
        return sb.toString();
    }

    // ==================== Message Sending ====================

    /**
     * Sends a MarkdownV2-formatted message to a Telegram chat.
     */
    @Override
    public void sendMessage(Long chatId, String text) throws TelegramApiException {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("MarkdownV2");
        message.setDisableWebPagePreview(true);
        execute(message);
    }

    // ==================== Parsing Helpers ====================

    private BigDecimal parseBigDecimal(String text) {
        try { return new BigDecimal(text.replace(",", ".")); }
        catch (NumberFormatException e) { return null; }
    }

    private Integer parseInteger(String text) {
        try { return Integer.parseInt(text.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    /** Comma-separated, escaped district list, shortened with "…" if very long. */
    private String formatDistricts(List<String> districts) {
        String joined = String.join(", ", districts);
        if (joined.length() > MAX_DISTRICTS_DISPLAY_LENGTH) {
            joined = joined.substring(0, MAX_DISTRICTS_DISPLAY_LENGTH) + "…";
        }
        return escape(joined);
    }

    /** Euro amount without needless decimals: 1200.00 → "1200", 1200.5 → "1200.50". */
    private static String euros(java.math.BigDecimal amount) {
        java.math.BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.toPlainString() : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** Escapes special characters for Telegram MarkdownV2. */
    private String escape(String text) {
        if (text == null) return "";
        return text.replaceAll("([_*\\[\\]()~`>#+\\-=|{}.!\\\\])", "\\\\$1");
    }

    // ==================== Inner Classes ====================

    /**
     * Holds the in-progress state of a /setcriteria conversation for one user.
     */
    static class CriteriaSession {

        enum Step { MAX_RENT, MIN_ROOMS, MAX_ROOMS, MIN_SIZE, MAX_SIZE, DISTRICTS }

        Step       step       = Step.MAX_RENT;
        BigDecimal maxRent;
        BigDecimal minRooms;
        BigDecimal maxRooms;
        Integer    minSizeSqm;
        Integer    maxSizeSqm;
        List<String> districts;
    }
}
