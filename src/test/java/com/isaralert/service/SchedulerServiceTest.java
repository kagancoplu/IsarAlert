package com.isaralert.service;

import com.isaralert.model.SearchCriteria;
import com.isaralert.model.User;
import com.isaralert.model.enums.ListingSource;
import com.isaralert.repository.ListingRepository;
import com.isaralert.repository.NotificationRepository;
import com.isaralert.repository.SearchCriteriaRepository;
import com.isaralert.service.scraper.ListingScraper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SchedulerService}.
 */
class SchedulerServiceTest {

    @Test
    @DisplayName("should skip a scan cycle triggered while another is still running")
    void skipsOverlappingCycles() throws Exception {
        ListingScraper scraper = mock(ListingScraper.class);
        SearchCriteriaRepository criteriaRepository = mock(SearchCriteriaRepository.class);
        // Real services over mocked repositories — inline mocking of concrete classes
        // isn't reliable on newer JDKs, and the scraper returns no listings anyway.
        ListingService listingService = new ListingService(mock(ListingRepository.class), criteriaRepository);
        NotificationService notificationService = new NotificationService(
                mock(NotificationRepository.class), mock(TelegramMessageSender.class));

        User user = User.builder().id(1L).telegramChatId(123L).active(true).build();
        SearchCriteria criteria = SearchCriteria.builder().id(1L).user(user).active(true).build();
        when(criteriaRepository.findAllByActiveTrueAndUserActiveTrue()).thenReturn(List.of(criteria));
        when(scraper.getSource()).thenReturn(ListingSource.WG_GESUCHT);

        // Block the first cycle inside the scraper until the second trigger has been attempted
        CountDownLatch firstCycleScraping = new CountDownLatch(1);
        CountDownLatch releaseFirstCycle = new CountDownLatch(1);
        when(scraper.scrape(any())).thenAnswer(inv -> {
            firstCycleScraping.countDown();
            releaseFirstCycle.await(5, TimeUnit.SECONDS);
            return List.of();
        });

        SchedulerService scheduler = new SchedulerService(
                List.of(scraper), listingService, notificationService, criteriaRepository);

        Thread first = new Thread(scheduler::runScanCycle);
        first.start();
        assertThat(firstCycleScraping.await(5, TimeUnit.SECONDS)).isTrue();

        scheduler.runScanCycle(); // should return immediately without scraping

        releaseFirstCycle.countDown();
        first.join(5000);

        verify(scraper, times(1)).scrape(any());

        // Once the first cycle finished, a new one is allowed again
        scheduler.runScanCycle();
        verify(scraper, times(2)).scrape(any());
    }
}
