package com.isaralert.integration;

import com.isaralert.model.Listing;
import com.isaralert.model.Notification;
import com.isaralert.model.SearchCriteria;
import com.isaralert.model.User;
import com.isaralert.model.enums.NotificationStatus;
import com.isaralert.repository.ListingRepository;
import com.isaralert.repository.NotificationRepository;
import com.isaralert.repository.SearchCriteriaRepository;
import com.isaralert.repository.UserRepository;
import com.isaralert.support.IntegrationTestBase;
import com.isaralert.support.WgPages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end scan cycles: scheduler → WG-Gesucht scraper (over HTTP, against the fake site)
 * → database → matching → notification → Telegram (fake API).
 */
@DisplayName("Scan pipeline")
class ScanPipelineIntegrationTest extends IntegrationTestBase {

    private static final String AU_HAIDHAUSEN = "/wohnungen-in-Muenchen-Au-Haidhausen.20000001.html";
    private static final String SENDLING = "/wohnungen-in-Muenchen-Sendling.20000002.html";
    private static final String SCHWABING = "/wohnungen-in-Muenchen-Schwabing-West.20000003.html";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SearchCriteriaRepository criteriaRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @AfterEach
    void everyMessageIsValidForTelegram() {
        assertNoRejectedMessages();
    }

    /** Creates an active user with one search; returns their chat ID. */
    private long userWithCriteria(UnaryOperator<SearchCriteria.SearchCriteriaBuilder> criteria) {
        long chatId = newChatId();
        User user = userRepository.save(User.builder().telegramChatId(chatId).firstName("Test").active(true).build());
        addCriteria(user, criteria);
        return chatId;
    }

    private void addCriteria(User user, UnaryOperator<SearchCriteria.SearchCriteriaBuilder> criteria) {
        criteriaRepository.save(criteria.apply(SearchCriteria.builder().user(user).active(true)).build());
    }

    private User user(long chatId) {
        return userRepository.findByTelegramChatId(chatId).orElseThrow();
    }

    private List<Notification> notificationsOf(long chatId) {
        return notificationRepository.findByUserId(user(chatId).getId());
    }

    /** Puts listings on search page 0, each with the given detail page. */
    private void siteHas(Object... pathsAndDetails) {
        String[] paths = new String[pathsAndDetails.length / 2];
        for (int i = 0; i < pathsAndDetails.length; i += 2) {
            paths[i / 2] = (String) pathsAndDetails[i];
            WG_GESUCHT.detailPage((String) pathsAndDetails[i], ((WgPages.Detail) pathsAndDetails[i + 1]).html());
        }
        WG_GESUCHT.searchPage(0, WgPages.searchPage(paths));
    }

    // ==================== Happy path ====================

    @Test
    @DisplayName("a matching listing is stored and sent with all its details")
    void matchingListingIsDelivered() {
        long chatId = userWithCriteria(c -> c.maxRent(new BigDecimal("1500")).districts(List.of("Haidhausen")));
        siteHas(AU_HAIDHAUSEN, WgPages.detail()
                .title("3-Zi. Altbau (renoviert) *top*!")
                .rent(1450).rooms("2,5").size(58)
                .address("Musterstraße 12", "81541 München Au-Haidhausen")
                .freeFrom("01.12.2026"));

        scheduler.runScanCycle();

        Listing listing = listingRepository.findAll().get(0);
        assertThat(listing.getExternalId()).isEqualTo("20000001");
        assertThat(listing.getPrice()).isEqualByComparingTo("1450");
        assertThat(listing.getRooms()).isEqualByComparingTo("2.5");
        assertThat(listing.getSizeSqm()).isEqualTo(58);
        assertThat(listing.getDistrict()).isEqualTo("Au Haidhausen");
        assertThat(listing.getAvailableFrom()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(listing.getUrl()).isEqualTo(WG_GESUCHT.baseUrl() + AU_HAIDHAUSEN);

        Notification notification = notificationsOf(chatId).get(0);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isNotNull();

        assertThat(TELEGRAM.lastTextTo(chatId)).contains(
                "3\\-Zi\\. Altbau \\(renoviert\\) \\*top\\*\\!",
                "€1450", "Rooms: 2\\.5", "58 m²", "Au Haidhausen", "2026\\-12\\-01",
                "[View Listing](" + WG_GESUCHT.baseUrl() + AU_HAIDHAUSEN + ")");
    }

    @Test
    @DisplayName("a listing that doesn't match is stored but not sent")
    void nonMatchingListingIsNotSent() {
        long chatId = userWithCriteria(c -> c.maxRent(new BigDecimal("1500")));
        siteHas(SENDLING, WgPages.detail().rent(2100));

        scheduler.runScanCycle();

        assertThat(listingRepository.count()).isEqualTo(1);
        assertThat(notificationsOf(chatId)).isEmpty();
        assertThat(TELEGRAM.sent()).isEmpty();
    }

    @Test
    @DisplayName("each criteria filter is applied (rent, rooms, size, district, U-Bahn)")
    void everyFilterIsApplied() {
        long rentUser     = userWithCriteria(c -> c.maxRent(new BigDecimal("1000")));
        long roomsUser    = userWithCriteria(c -> c.minRooms(new BigDecimal("3")));
        long sizeUser     = userWithCriteria(c -> c.minSizeSqm(60));
        long districtUser = userWithCriteria(c -> c.districts(List.of("Schwabing")));
        long ubahnUser    = userWithCriteria(c -> c.ubahnLines(List.of("U4")));   // serves Haidhausen, not Sendling
        siteHas(
                AU_HAIDHAUSEN, WgPages.detail().rent(900).rooms("3").size(70)
                        .address("A-Str. 1", "81541 München Au-Haidhausen"),
                SENDLING, WgPages.detail().rent(1400).rooms("1").size(30)
                        .address("B-Str. 2", "81369 München Sendling"));

        scheduler.runScanCycle();

        // Only the Au-Haidhausen listing (cheap, 3 rooms, 70 m², U4) passes these filters
        Long auHaidhausenId = listingRepository.findAll().stream()
                .filter(l -> l.getExternalId().equals("20000001")).findFirst().orElseThrow().getId();
        for (long chatId : List.of(rentUser, roomsUser, sizeUser, ubahnUser)) {
            assertThat(notificationsOf(chatId)).as("chat %d", chatId).hasSize(1)
                    .allSatisfy(n -> assertThat(n.getListing().getId()).isEqualTo(auHaidhausenId));
        }
        assertThat(notificationsOf(districtUser)).isEmpty();
    }

    @Test
    @DisplayName("fields the scraper couldn't read don't filter a listing out")
    void unparseableFieldsLetListingThrough() {
        long chatId = userWithCriteria(c -> c.maxRent(new BigDecimal("800")).minRooms(new BigDecimal("2")));
        WG_GESUCHT.searchPage(0, WgPages.searchPage(SCHWABING))
                .detailPage(SCHWABING, "<html><body><h1>Nur Text, keine Eckdaten</h1></body></html>");

        scheduler.runScanCycle();

        Listing listing = listingRepository.findAll().get(0);
        assertThat(listing.getPrice()).isNull();
        assertThat(listing.getRooms()).isNull();
        assertThat(listing.getDistrict()).isEqualTo("Schwabing West");  // from the URL
        assertThat(notificationsOf(chatId)).hasSize(1);
    }

    // ==================== Who gets notified ====================

    @Nested
    @DisplayName("recipients")
    class Recipients {

        @Test
        @DisplayName("every matching user gets the listing, the listing is stored once")
        void multipleUsers() {
            long anna = userWithCriteria(c -> c.maxRent(new BigDecimal("2000")));
            long ben = userWithCriteria(c -> c.maxRent(new BigDecimal("1500")));
            long carl = userWithCriteria(c -> c.maxRent(new BigDecimal("1000")));
            siteHas(SENDLING, WgPages.detail().rent(1400));

            scheduler.runScanCycle();

            assertThat(listingRepository.count()).isEqualTo(1);
            assertThat(TELEGRAM.textsTo(anna)).hasSize(1);
            assertThat(TELEGRAM.textsTo(ben)).hasSize(1);
            assertThat(TELEGRAM.textsTo(carl)).isEmpty();
        }

        @Test
        @DisplayName("a user whose several searches match gets the listing only once")
        void severalMatchingCriteriaOneMessage() {
            long chatId = userWithCriteria(c -> c.maxRent(new BigDecimal("2000")));
            addCriteria(user(chatId), c -> c.districts(List.of("Sendling")));
            siteHas(SENDLING, WgPages.detail().rent(1400).address("B-Str. 2", "81369 München Sendling"));

            scheduler.runScanCycle();

            assertThat(notificationsOf(chatId)).hasSize(1);
            assertThat(TELEGRAM.textsTo(chatId)).hasSize(1);
        }

        @Test
        @DisplayName("users who sent /stop get nothing, and nothing is scraped for them")
        void pausedUsers() {
            long chatId = userWithCriteria(c -> c);
            User user = user(chatId);
            user.setActive(false);
            userRepository.save(user);
            siteHas(SENDLING, WgPages.detail());

            scheduler.runScanCycle();

            assertThat(WG_GESUCHT.requests()).isEmpty();
            assertThat(TELEGRAM.sent()).isEmpty();
        }

        @Test
        @DisplayName("paused searches are ignored")
        void pausedCriteria() {
            long chatId = newChatId();
            User user = userRepository.save(User.builder().telegramChatId(chatId).firstName("T").active(true).build());
            criteriaRepository.save(SearchCriteria.builder().user(user).active(false).build());
            siteHas(SENDLING, WgPages.detail());

            scheduler.runScanCycle();

            assertThat(WG_GESUCHT.requests()).isEmpty();
            assertThat(TELEGRAM.sent()).isEmpty();
        }

        @Test
        @DisplayName("with no searches at all, WG-Gesucht isn't contacted")
        void noCriteria() {
            siteHas(SENDLING, WgPages.detail());

            scheduler.runScanCycle();

            assertThat(WG_GESUCHT.requests()).isEmpty();
        }
    }

    // ==================== Repeated scans ====================

    @Nested
    @DisplayName("repeated scans")
    class RepeatedScans {

        @Test
        @DisplayName("a listing is never sent twice and its page is fetched only once")
        void noDuplicates() {
            long chatId = userWithCriteria(c -> c);
            siteHas(SENDLING, WgPages.detail());

            scheduler.runScanCycle();
            scheduler.runScanCycle();
            scheduler.runScanCycle();

            assertThat(listingRepository.count()).isEqualTo(1);
            assertThat(TELEGRAM.textsTo(chatId)).hasSize(1);
            assertThat(WG_GESUCHT.requestCount(SENDLING)).isEqualTo(1);
        }

        @Test
        @DisplayName("a listing appearing later is picked up by the next scan")
        void newListingNextScan() {
            long chatId = userWithCriteria(c -> c);
            siteHas(SENDLING, WgPages.detail().title("Erste"));
            scheduler.runScanCycle();

            siteHas(SENDLING, WgPages.detail().title("Erste"), SCHWABING, WgPages.detail().title("Zweite"));
            scheduler.runScanCycle();

            assertThat(TELEGRAM.textsTo(chatId)).hasSize(2);
            assertThat(TELEGRAM.lastTextTo(chatId)).contains("Zweite");
        }
    }

    // ==================== Scraping ====================

    @Nested
    @DisplayName("scraping")
    class Scraping {

        @Test
        @DisplayName("search filters are sent to WG-Gesucht in the URL")
        void searchUrlCarriesFilters() {
            userWithCriteria(c -> c.maxRent(new BigDecimal("1200.99")).minSizeSqm(40).maxSizeSqm(80));

            scheduler.runScanCycle();

            assertThat(WG_GESUCHT.searchRequests().get(0))
                    .isEqualTo("/wohnungen-in-Muenchen.90.2.1.0.html?rMax=1200&sMin=40&sMax=80");
        }

        @Test
        @DisplayName("stops paging at the first empty results page")
        void stopsAtEmptyPage() {
            userWithCriteria(c -> c);
            WG_GESUCHT.searchPage(0, WgPages.searchPage(SENDLING)).detailPage(SENDLING, WgPages.detail().html());

            scheduler.runScanCycle();

            assertThat(WG_GESUCHT.searchRequests()).hasSize(2);  // page 0, then empty page 1
        }

        @Test
        @DisplayName("reads at most 3 result pages")
        void readsAtMostThreePages() {
            userWithCriteria(c -> c);
            for (int page = 0; page < 5; page++) {
                String path = "/wohnungen-in-Muenchen-Laim.3000000" + page + ".html";
                WG_GESUCHT.searchPage(page, WgPages.searchPage(path)).detailPage(path, WgPages.detail().html());
            }

            scheduler.runScanCycle();

            assertThat(WG_GESUCHT.searchRequests()).hasSize(3);
            assertThat(listingRepository.count()).isEqualTo(3);
        }

        @Test
        @DisplayName("an unreachable detail page still yields a listing from the search result")
        void detailPageDown() {
            long chatId = userWithCriteria(c -> c.districts(List.of("Sendling")));
            WG_GESUCHT.searchPage(0, WgPages.searchPage(SENDLING)).detailPageFails(SENDLING, 500);

            scheduler.runScanCycle();

            Listing listing = listingRepository.findAll().get(0);
            assertThat(listing.getTitle()).isEqualTo("Listing " + SENDLING);   // link text from the search page
            assertThat(listing.getDistrict()).isEqualTo("Sendling");
            assertThat(listing.getPrice()).isNull();
            assertThat(TELEGRAM.textsTo(chatId)).hasSize(1);
        }

        @Test
        @DisplayName("WG-Gesucht being down doesn't break the cycle, and the next scan recovers")
        void searchPageDown() {
            long chatId = userWithCriteria(c -> c);
            WG_GESUCHT.searchPageFails(0, 503);

            scheduler.runScanCycle();
            assertThat(listingRepository.count()).isZero();

            siteHas(SENDLING, WgPages.detail());
            scheduler.runScanCycle();
            assertThat(TELEGRAM.textsTo(chatId)).hasSize(1);
        }
    }

    // ==================== Telegram failures & retries ====================

    @Nested
    @DisplayName("delivery failures")
    class DeliveryFailures {

        @Test
        @DisplayName("a failed message is retried on the next scan once Telegram is back")
        void retryAfterOutage() {
            long chatId = userWithCriteria(c -> c);
            siteHas(SENDLING, WgPages.detail());
            TELEGRAM.failWith(502, "Bad Gateway");

            scheduler.runScanCycle();
            Notification failed = notificationsOf(chatId).get(0);
            assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
            assertThat(failed.getErrorMessage()).startsWith("[retry:1]").contains("Bad Gateway");

            TELEGRAM.recover();
            scheduler.runScanCycle();

            Notification sent = notificationsOf(chatId).get(0);
            assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(sent.getErrorMessage()).isNull();
            assertThat(TELEGRAM.textsTo(chatId)).hasSize(1);
        }

        @Test
        @DisplayName("after 3 failed retries a message is given up on")
        void givesUpAfterThreeRetries() {
            long chatId = userWithCriteria(c -> c);
            siteHas(SENDLING, WgPages.detail());
            TELEGRAM.failWith(502, "Bad Gateway");

            for (int i = 0; i < 4; i++) {
                scheduler.runScanCycle();
            }
            assertThat(notificationsOf(chatId).get(0).getErrorMessage()).startsWith("[retry:3]");

            TELEGRAM.recover();
            scheduler.runScanCycle();

            assertThat(notificationsOf(chatId).get(0).getStatus()).isEqualTo(NotificationStatus.FAILED);
            assertThat(TELEGRAM.textsTo(chatId)).isEmpty();
        }

        @Test
        @DisplayName("failed messages aren't retried for users who paused in the meantime")
        void noRetryForPausedUser() {
            long chatId = userWithCriteria(c -> c);
            siteHas(SENDLING, WgPages.detail());
            TELEGRAM.failWith(403, "Forbidden: bot was blocked by the user");
            scheduler.runScanCycle();

            User user = user(chatId);
            user.setActive(false);
            userRepository.save(user);
            TELEGRAM.recover();
            scheduler.runScanCycle();

            assertThat(TELEGRAM.textsTo(chatId)).isEmpty();
        }
    }
}
