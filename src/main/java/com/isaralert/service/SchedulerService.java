package com.isaralert.service;

import com.isaralert.dto.ScrapedListingDto;
import com.isaralert.event.ScanRequestedEvent;
import com.isaralert.model.Listing;
import com.isaralert.model.SearchCriteria;
import com.isaralert.service.scraper.ListingScraper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates the periodic scraping cycle:
 * <ol>
 *     <li>Fetches all active search criteria</li>
 *     <li>Runs each scraper for each criteria</li>
 *     <li>Deduplicates and saves new listings via {@link ListingService}</li>
 *     <li>Matches new listings to users and sends notifications via {@link NotificationService}</li>
 * </ol>
 *
 * <p>The cron expression is configured via {@code isaralert.scraper.cron}
 * in application.yml, overridable via {@code SCRAPER_CRON} (default: every 5 minutes).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "isaralert.scraper.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerService {

    private final List<ListingScraper> scrapers;
    private final ListingService listingService;
    private final NotificationService notificationService;
    private final com.isaralert.repository.SearchCriteriaRepository searchCriteriaRepository;

    /** Prevents a manual /forcescan from overlapping with a scheduled cycle (or another manual one). */
    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);

    /**
     * Main scanning cycle — triggered by Spring's scheduler.
     * Skipped if another cycle is still running.
     */
    @Scheduled(cron = "${isaralert.scraper.cron:0 */15 * * * *}")
    public void runScanCycle() {
        if (!scanInProgress.compareAndSet(false, true)) {
            log.info("Scan cycle already in progress — skipping this trigger");
            return;
        }
        try {
            doScanCycle();
        } finally {
            scanInProgress.set(false);
        }
    }

    private void doScanCycle() {
        Instant start = Instant.now();
        log.info("========== 🔍 Starting scan cycle ==========");

        List<SearchCriteria> activeCriteria = searchCriteriaRepository.findAllByActiveTrueAndUserActiveTrue();
        if (activeCriteria.isEmpty()) {
            log.info("No active search criteria found — skipping scan");
            return;
        }

        log.info("Found {} active search criteria, {} scrapers available",
                activeCriteria.size(), scrapers.size());

        int totalNew = 0;
        int totalNotifications = 0;

        for (SearchCriteria criteria : activeCriteria) {
            for (ListingScraper scraper : scrapers) {
                try {
                    log.info("Scraping {} for criteria id={}", scraper.getSource(), criteria.getId());

                    // 1. Scrape
                    List<ScrapedListingDto> scraped = scraper.scrape(criteria);
                    log.info("Scraped {} listings from {}", scraped.size(), scraper.getSource());

                    // 2. Deduplicate & save
                    List<Listing> newListings = listingService.processScrapedListings(scraped);
                    totalNew += newListings.size();

                    // 3. Match & notify
                    for (Listing listing : newListings) {
                        List<SearchCriteria> matchingCriteria = listingService.findMatchingCriteria(listing);
                        for (SearchCriteria match : matchingCriteria) {
                            notificationService.notifyUser(match.getUser(), listing);
                            totalNotifications++;
                        }
                    }

                } catch (Exception e) {
                    log.error("Error during {} scrape for criteria {}: {}",
                            scraper.getSource(), criteria.getId(), e.getMessage(), e);
                    // Continue with other scrapers/criteria — don't fail the entire cycle
                }
            }
        }

        Duration elapsed = Duration.between(start, Instant.now());
        log.info("========== ✅ Scan cycle complete: {} new listings, {} notifications sent ({} seconds) ==========",
                totalNew, totalNotifications, elapsed.toSeconds());

        // Retry any previously failed notifications
        try {
            notificationService.retryFailedNotifications();
        } catch (Exception e) {
            log.error("Error during notification retry cycle: {}", e.getMessage(), e);
        }
    }

    /**
     * Handles a manual scan request published by TelegramBotService (/forcescan).
     * Using an event decouples the two services and avoids a circular dependency.
     */
    @Async
    @EventListener
    public void onScanRequested(ScanRequestedEvent event) {
        log.info("Manual scan requested by chatId={}", event.getRequestedByChatId());
        runScanCycle();
    }
}
