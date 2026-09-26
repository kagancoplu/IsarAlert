package com.isaralert.integration;

import com.isaralert.model.SearchCriteria;
import com.isaralert.model.User;
import com.isaralert.repository.SearchCriteriaRepository;
import com.isaralert.repository.UserRepository;
import com.isaralert.support.IntegrationTestBase;
import com.isaralert.support.WgPages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

/**
 * Talks to the bot the way a Telegram user would — every scenario you'd otherwise test by hand
 * in the Telegram app. Replies are captured by the fake Telegram API, which also rejects any
 * reply Telegram itself would reject (invalid MarkdownV2, too long).
 */
@DisplayName("Telegram bot")
class TelegramBotIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SearchCriteriaRepository criteriaRepository;

    @AfterEach
    void everyReplyIsValidForTelegram() {
        assertNoRejectedMessages();
    }

    private User user(long chatId) {
        return userRepository.findByTelegramChatId(chatId).orElseThrow();
    }

    private List<SearchCriteria> criteria(long chatId) {
        return criteriaRepository.findByUserId(user(chatId).getId());
    }

    private long registeredUser() {
        long chatId = newChatId();
        userSends(chatId, "/start");
        return chatId;
    }

    /** Runs the whole /setcriteria wizard with the given answers. */
    private void completeWizard(long chatId, String rent, String minRooms, String maxRooms,
                                String minSize, String maxSize, String districts) {
        userSends(chatId, "/setcriteria");
        for (String answer : List.of(rent, minRooms, maxRooms, minSize, maxSize, districts)) {
            userSends(chatId, answer);
        }
    }

    // ==================== Registration ====================

    @Nested
    @DisplayName("/start and /stop")
    class Registration {

        @Test
        @DisplayName("/start registers a new active user and welcomes them by name")
        void startRegistersUser() {
            long chatId = newChatId();

            userSends(chatId, "/start");

            User user = user(chatId);
            assertThat(user.getActive()).isTrue();
            assertThat(user.getFirstName()).isEqualTo("Kagan");
            assertThat(user.getUsername()).isEqualTo("user" + chatId);
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("Welcome to IsarAlert, Kagan\\!");
        }

        @Test
        @DisplayName("/start twice doesn't create a second user")
        void startIsIdempotent() {
            long chatId = newChatId();

            userSends(chatId, "/start");
            userSends(chatId, "/start");

            assertThat(userRepository.findAll()).filteredOn(u -> u.getTelegramChatId() == chatId).hasSize(1);
            assertThat(TELEGRAM.textsTo(chatId)).hasSize(2);
        }

        @Test
        @DisplayName("names with Markdown characters are escaped")
        void escapesSpecialCharactersInName() {
            long chatId = newChatId();

            userSends(chatId, "Anne-Marie (Test)_1!", "/start");

            assertThat(TELEGRAM.lastTextTo(chatId)).contains("Anne\\-Marie \\(Test\\)\\_1\\!");
        }

        @Test
        @DisplayName("/stop pauses the user and /start resumes them")
        void stopAndResume() {
            long chatId = registeredUser();

            userSends(chatId, "/stop");
            assertThat(user(chatId).getActive()).isFalse();
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("Notifications paused");

            userSends(chatId, "/start");
            assertThat(user(chatId).getActive()).isTrue();
        }

        @Test
        @DisplayName("/stop from an unknown chat doesn't create a user")
        void stopWhenNotRegistered() {
            long chatId = newChatId();

            userSends(chatId, "/stop");

            assertThat(userRepository.findByTelegramChatId(chatId)).isEmpty();
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("not registered");
        }
    }

    // ==================== Command parsing ====================

    @Nested
    @DisplayName("command parsing")
    class CommandParsing {

        @Test
        @DisplayName("group-chat commands like /start@IsarAlertBot work")
        void commandWithBotName() {
            long chatId = newChatId();

            userSends(chatId, "/start@IsarAlertBot");

            assertThat(userRepository.findByTelegramChatId(chatId)).isPresent();
        }

        @Test
        @DisplayName("commands are case-insensitive and ignore extra words")
        void caseAndArguments() {
            long chatId = newChatId();

            userSends(chatId, "  /START   please ");
            userSends(chatId, "/Help");

            assertThat(userRepository.findByTelegramChatId(chatId)).isPresent();
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("IsarAlert Commands");
        }

        @Test
        @DisplayName("/help lists every command")
        void helpListsCommands() {
            long chatId = newChatId();

            userSends(chatId, "/help");

            assertThat(TELEGRAM.lastTextTo(chatId))
                    .contains("/start", "/setcriteria", "/search", "/forcescan", "/cancel", "/stop", "/help");
        }

        @Test
        @DisplayName("unknown commands get a hint")
        void unknownCommand() {
            long chatId = newChatId();

            userSends(chatId, "/dance");

            assertThat(TELEGRAM.lastTextTo(chatId)).contains("Unknown command");
        }

        @Test
        @DisplayName("plain text outside the wizard gets a hint")
        void plainTextWithoutWizard() {
            long chatId = registeredUser();

            userSends(chatId, "hello bot");

            assertThat(TELEGRAM.lastTextTo(chatId)).contains("didn't understand");
        }

        @Test
        @DisplayName("updates without text (stickers, photos, edits) are ignored")
        void nonTextUpdatesAreIgnored() {
            long chatId = newChatId();

            Update noMessage = new Update();
            Message photo = new Message();
            photo.setChat(new Chat(chatId, "private"));
            Update photoUpdate = new Update();
            photoUpdate.setMessage(photo);

            bot.onUpdateReceived(noMessage);
            bot.onUpdateReceived(photoUpdate);

            assertThat(TELEGRAM.sent()).isEmpty();
        }

        @Test
        @DisplayName("/start without a sender (e.g. from a channel) registers the chat with a generic greeting")
        void startWithoutSender() {
            long chatId = newChatId();
            Message message = new Message();
            message.setChat(new Chat(chatId, "channel"));
            message.setText("/start");
            Update update = new Update();
            update.setMessage(message);

            bot.onUpdateReceived(update);

            assertThat(user(chatId).getFirstName()).isNull();
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("Welcome to IsarAlert, there\\!");
        }

        @Test
        @DisplayName("messages without a sender (e.g. channel posts) still get an answer")
        void messageWithoutSender() {
            long chatId = newChatId();
            Message message = new Message();
            message.setChat(new Chat(chatId, "channel"));
            message.setText("/help");
            Update update = new Update();
            update.setMessage(message);

            bot.onUpdateReceived(update);

            assertThat(TELEGRAM.lastTextTo(chatId)).contains("IsarAlert Commands");
        }
    }

    // ==================== /setcriteria wizard ====================

    @Nested
    @DisplayName("/setcriteria wizard")
    class Wizard {

        @Test
        @DisplayName("saves all answers, including decimal commas and district lists")
        void savesAllAnswers() {
            long chatId = registeredUser();

            completeWizard(chatId, "1200.50", "2", "3,5", "40", "90", "Maxvorstadt, Schwabing");

            List<SearchCriteria> saved = criteria(chatId);
            assertThat(saved).hasSize(1);
            SearchCriteria c = saved.get(0);
            assertThat(c.getMaxRent()).isEqualByComparingTo("1200.50");
            assertThat(c.getMinRooms()).isEqualByComparingTo("2");
            assertThat(c.getMaxRooms()).isEqualByComparingTo("3.5");
            assertThat(c.getMinSizeSqm()).isEqualTo(40);
            assertThat(c.getMaxSizeSqm()).isEqualTo(90);
            assertThat(c.getDistricts()).containsExactly("Maxvorstadt", "Schwabing");
            assertThat(c.getActive()).isTrue();
            assertThat(TELEGRAM.lastTextTo(chatId))
                    .contains("Search criteria saved", "€1200\\.50", "3\\.5", "40 m²", "Maxvorstadt, Schwabing");
        }

        @Test
        @DisplayName("'skip' (any case) leaves every field empty")
        void skipEverything() {
            long chatId = registeredUser();

            completeWizard(chatId, "skip", "SKIP", "Skip", "skip", "skip", "skip");

            SearchCriteria c = criteria(chatId).get(0);
            assertThat(c.getMaxRent()).isNull();
            assertThat(c.getMinRooms()).isNull();
            assertThat(c.getMaxRooms()).isNull();
            assertThat(c.getMinSizeSqm()).isNull();
            assertThat(c.getMaxSizeSqm()).isNull();
            assertThat(c.getDistricts()).isNull();
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("Max rent: any");
        }

        @Test
        @DisplayName("invalid answers are rejected and the same question is asked again")
        void invalidAnswersRepeatTheStep() {
            long chatId = registeredUser();
            userSends(chatId, "/setcriteria");

            for (String bad : List.of("abc", "-100", "0", "12OO")) {
                userSends(chatId, bad);
                assertThat(TELEGRAM.lastTextTo(chatId)).as("answer '%s'", bad).contains("valid amount");
            }
            userSends(chatId, "1200");
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("Step 2/6");

            userSends(chatId, "zwei");
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("valid room count");
            userSends(chatId, "0");
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("valid room count");
            userSends(chatId, "2");
            userSends(chatId, "-1");
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("valid room count");
            userSends(chatId, "3");

            userSends(chatId, "40.5");  // sizes are whole m²
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("valid size");
            userSends(chatId, "-5");
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("valid size");
            userSends(chatId, "40");
            userSends(chatId, "0");
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("valid size");
            userSends(chatId, "Riesig");
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("valid size");

            assertThat(criteria(chatId)).as("nothing saved before the wizard finishes").isEmpty();
        }

        @Test
        @DisplayName("maximum rooms/size below the minimum are rejected")
        void maxBelowMinIsRejected() {
            long chatId = registeredUser();
            userSends(chatId, "/setcriteria");
            userSends(chatId, "1000");
            userSends(chatId, "3");

            userSends(chatId, "2");
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("can't be less than your minimum");
            userSends(chatId, "3");                       // equal to the minimum is fine
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("Step 4/6");

            userSends(chatId, "60");
            userSends(chatId, "59");
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("can't be less than your minimum");
            userSends(chatId, "80");
            userSends(chatId, "skip");

            SearchCriteria c = criteria(chatId).get(0);
            assertThat(c.getMaxRooms()).isEqualByComparingTo("3");
            assertThat(c.getMaxSizeSqm()).isEqualTo(80);
        }

        @Test
        @DisplayName("district answers are trimmed and empty entries dropped")
        void districtListIsCleaned() {
            long chatId = registeredUser();

            completeWizard(chatId, "skip", "skip", "skip", "skip", "skip", " Au-Haidhausen , ,Schwabing ,  ");

            assertThat(criteria(chatId).get(0).getDistricts()).containsExactly("Au-Haidhausen", "Schwabing");
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("Au\\-Haidhausen, Schwabing");
        }

        @Test
        @DisplayName("a huge pasted district list is saved and the summary is shortened to fit")
        void hugeDistrictList() {
            long chatId = registeredUser();
            String districts = String.join(", ", java.util.Collections.nCopies(400, "Maxvorstadt-Nord"));

            completeWizard(chatId, "skip", "skip", "skip", "skip", "skip", districts);

            assertThat(criteria(chatId).get(0).getDistricts()).hasSize(400);
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("Search criteria saved", "…");
        }

        @Test
        @DisplayName("/cancel aborts the wizard without saving")
        void cancelAborts() {
            long chatId = registeredUser();
            userSends(chatId, "/setcriteria");
            userSends(chatId, "1200");

            userSends(chatId, "/cancel");
            userSends(chatId, "2");

            assertThat(criteria(chatId)).isEmpty();
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("didn't understand");
        }

        @Test
        @DisplayName("other commands in the middle of the wizard don't lose progress")
        void commandsDuringWizardKeepProgress() {
            long chatId = registeredUser();
            userSends(chatId, "/setcriteria");
            userSends(chatId, "1500");

            userSends(chatId, "/help");
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("IsarAlert Commands");

            for (String answer : List.of("2", "3", "skip", "skip", "skip")) {
                userSends(chatId, answer);
            }

            assertThat(criteria(chatId).get(0).getMaxRent()).isEqualByComparingTo("1500");
        }

        @Test
        @DisplayName("/setcriteria during the wizard starts over from step 1")
        void restartWizard() {
            long chatId = registeredUser();
            userSends(chatId, "/setcriteria");
            userSends(chatId, "1500");
            userSends(chatId, "2");

            completeWizard(chatId, "900", "skip", "skip", "skip", "skip", "skip");

            List<SearchCriteria> saved = criteria(chatId);
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getMaxRent()).isEqualByComparingTo("900");
            assertThat(saved.get(0).getMinRooms()).isNull();
        }

        @Test
        @DisplayName("/setcriteria needs /start first")
        void requiresRegistration() {
            long chatId = newChatId();

            userSends(chatId, "/setcriteria");
            userSends(chatId, "1200");

            assertThat(userRepository.findByTelegramChatId(chatId)).isEmpty();
            assertThat(TELEGRAM.textsTo(chatId).get(0)).contains("/start first");
        }

        @Test
        @DisplayName("running the wizard again adds another search")
        void secondWizardAddsCriteria() {
            long chatId = registeredUser();

            completeWizard(chatId, "1000", "skip", "skip", "skip", "skip", "skip");
            completeWizard(chatId, "2000", "skip", "skip", "skip", "skip", "Sendling");

            assertThat(criteria(chatId)).extracting(SearchCriteria::getMaxRent)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactlyInAnyOrder(new BigDecimal("1000"), new BigDecimal("2000"));
        }
    }

    // ==================== /search ====================

    @Nested
    @DisplayName("/search")
    class Search {

        @Test
        @DisplayName("needs /start first")
        void notRegistered() {
            long chatId = newChatId();

            userSends(chatId, "/search");

            assertThat(TELEGRAM.lastTextTo(chatId)).contains("not registered");
        }

        @Test
        @DisplayName("says so when there are no criteria yet")
        void noCriteria() {
            long chatId = registeredUser();

            userSends(chatId, "/search");

            assertThat(TELEGRAM.lastTextTo(chatId)).contains("No search criteria yet");
        }

        @Test
        @DisplayName("a single search is shown without numbering")
        void singleCriteria() {
            long chatId = registeredUser();
            completeWizard(chatId, "skip", "1", "2", "skip", "skip", "skip");

            userSends(chatId, "/search");

            assertThat(TELEGRAM.lastTextTo(chatId))
                    .contains("Your Search Criteria", "Rooms: 1 \\- 2", "Max rent: any")
                    .doesNotContain("Criteria 1", "Districts");
        }

        @Test
        @DisplayName("lists every search, including paused ones")
        void listsCriteria() {
            long chatId = registeredUser();
            completeWizard(chatId, "1200", "2", "3", "40", "80", "Au-Haidhausen");
            completeWizard(chatId, "skip", "skip", "skip", "skip", "skip", "skip");
            jdbc.update("UPDATE search_criteria SET active = false WHERE max_rent IS NULL");

            userSends(chatId, "/search");

            assertThat(TELEGRAM.lastTextTo(chatId))
                    .contains("*Criteria 1:*", "*Criteria 2:*", "€1200", "Au\\-Haidhausen", "✅ Active", "⏸️ Paused");
        }

        @Test
        @DisplayName("a very long list is split into several messages instead of failing")
        void longListIsSplit() {
            long chatId = registeredUser();
            Long userId = user(chatId).getId();
            for (int i = 0; i < 60; i++) {
                jdbc.update("INSERT INTO search_criteria (user_id, max_rent, districts) VALUES (?, ?, ?::text[])",
                        userId, 1000 + i, "{Maxvorstadt,Schwabing,Haidhausen,Sendling,Neuhausen}");
            }
            int before = TELEGRAM.textsTo(chatId).size();

            userSends(chatId, "/search");

            List<String> replies = TELEGRAM.textsTo(chatId).subList(before, TELEGRAM.textsTo(chatId).size());
            assertThat(replies).hasSizeGreaterThan(1);
            assertThat(String.join("\n", replies)).contains("*Criteria 1:*", "*Criteria 60:*");
        }
    }

    // ==================== /forcescan ====================

    @Nested
    @DisplayName("/forcescan")
    class ForceScan {

        @Test
        @DisplayName("needs /start first")
        void notRegistered() {
            long chatId = newChatId();

            userSends(chatId, "/forcescan");

            assertThat(TELEGRAM.lastTextTo(chatId)).contains("/start first");
            assertThat(WG_GESUCHT.requests()).isEmpty();
        }

        @Test
        @DisplayName("runs a scan right away and delivers matches")
        void triggersScan() {
            long chatId = registeredUser();
            completeWizard(chatId, "1500", "skip", "skip", "skip", "skip", "skip");
            String path = "/wohnungen-in-Muenchen-Sendling.10000001.html";
            WG_GESUCHT.searchPage(0, WgPages.searchPage(path))
                    .detailPage(path, WgPages.detail().title("Forcescan Wohnung").rent(1100).html());

            userSends(chatId, "/forcescan");

            assertThat(TELEGRAM.lastTextTo(chatId)).satisfiesAnyOf(
                    text -> assertThat(text).contains("Starting manual scan"),
                    text -> assertThat(text).contains("Forcescan Wohnung"));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(TELEGRAM.textsTo(chatId)).anyMatch(t -> t.contains("Forcescan Wohnung")));
        }

        @Test
        @DisplayName("can't be repeated within the cooldown")
        void cooldown() {
            long chatId = registeredUser();

            userSends(chatId, "/forcescan");
            userSends(chatId, "/forcescan");

            assertThat(TELEGRAM.lastTextTo(chatId)).contains("Please wait 10 more minute");
        }
    }

    // ==================== Telegram outages ====================

    @Test
    @DisplayName("a Telegram outage during /stop still pauses the user")
    void telegramOutageDuringStop() {
        long chatId = registeredUser();
        long strangerChatId = newChatId();
        TELEGRAM.failWith(502, "Bad Gateway");

        assertThatCode(() -> {
            userSends(chatId, "/stop");
            userSends(strangerChatId, "/stop");
        }).doesNotThrowAnyException();

        assertThat(user(chatId).getActive()).isFalse();
    }

    @Test
    @DisplayName("a Telegram outage while replying doesn't crash the bot or lose the registration")
    void telegramOutageWhileReplying() {
        long chatId = newChatId();
        TELEGRAM.failWith(502, "Bad Gateway");

        assertThatCode(() -> userSends(chatId, "/start")).doesNotThrowAnyException();

        assertThat(user(chatId).getActive()).isTrue();
        TELEGRAM.recover();
        userSends(chatId, "/help");
        assertThat(TELEGRAM.lastTextTo(chatId)).contains("IsarAlert Commands");
    }
}
