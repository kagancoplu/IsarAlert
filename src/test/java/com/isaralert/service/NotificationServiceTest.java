package com.isaralert.service;

import com.isaralert.model.Listing;
import com.isaralert.model.Notification;
import com.isaralert.model.User;
import com.isaralert.model.enums.ListingSource;
import com.isaralert.model.enums.NotificationStatus;
import com.isaralert.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private TelegramMessageSender telegramBotService;

    @InjectMocks
    private NotificationService notificationService;

    private User testUser() {
        return User.builder()
                .id(1L)
                .telegramChatId(123456789L)
                .firstName("Kagan")
                .username("kagan")
                .build();
    }

    private Listing testListing() {
        return Listing.builder()
                .id(1L)
                .externalId("12345")
                .source(ListingSource.WG_GESUCHT)
                .title("2-Zi Wohnung Maxvorstadt")
                .price(new BigDecimal("1100.00"))
                .rooms(new BigDecimal("2.0"))
                .sizeSqm(55)
                .address("München Maxvorstadt")
                .district("Maxvorstadt")
                .url("https://www.wg-gesucht.de/12345.html")
                .build();
    }

    @Test
    @DisplayName("should send notification and mark as SENT on success")
    void shouldSendAndMarkSent() throws Exception {
        User user = testUser();
        Listing listing = testListing();

        when(notificationRepository.existsByUserIdAndListingId(1L, 1L)).thenReturn(false);
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(telegramBotService).sendMessage(eq(123456789L), any());

        notificationService.notifyUser(user, listing);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());

        Notification saved = captor.getAllValues().get(1); // Second save = after sending
        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(saved.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("should mark as FAILED when Telegram send fails")
    void shouldMarkFailedOnError() throws Exception {
        User user = testUser();
        Listing listing = testListing();

        when(notificationRepository.existsByUserIdAndListingId(1L, 1L)).thenReturn(false);
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        doThrow(new TelegramApiException("Network error"))
                .when(telegramBotService).sendMessage(eq(123456789L), any());

        notificationService.notifyUser(user, listing);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());

        Notification saved = captor.getAllValues().get(1);
        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(saved.getErrorMessage()).contains("Network error");
    }

    @Test
    @DisplayName("should skip if user already notified about this listing")
    void shouldSkipDuplicate() {
        User user = testUser();
        Listing listing = testListing();

        when(notificationRepository.existsByUserIdAndListingId(1L, 1L)).thenReturn(true);

        notificationService.notifyUser(user, listing);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a listing with only the required fields still produces a valid Telegram message")
    void minimalListingMessage() throws Exception {
        User user = testUser();
        Listing listing = Listing.builder().id(2L).externalId("1").source(ListingSource.WG_GESUCHT)
                .title("Nur ein Titel").url("https://www.wg-gesucht.de/1.html").build();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        notificationService.notifyUser(user, listing);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramBotService).sendMessage(eq(123456789L), text.capture());
        assertThat(text.getValue()).doesNotContain("Price", "Rooms", "Size", "Location", "District", "Available");
        assertThat(com.isaralert.support.MarkdownV2.validate(text.getValue())).isEmpty();
    }

    @Test
    @DisplayName("retry should resend FAILED notifications for active users")
    void retryResendsForActiveUser() throws Exception {
        User user = testUser();
        user.setActive(true);
        Notification failed = Notification.builder()
                .id(10L).user(user).listing(testListing())
                .status(NotificationStatus.FAILED).errorMessage("Network error")
                .build();

        when(notificationRepository.findByStatus(NotificationStatus.FAILED)).thenReturn(java.util.List.of(failed));

        notificationService.retryFailedNotifications();

        verify(telegramBotService).sendMessage(eq(123456789L), any());
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("retry should skip FAILED notifications for users who paused via /stop")
    void retrySkipsInactiveUser() throws Exception {
        User user = testUser();
        user.setActive(false);
        Notification failed = Notification.builder()
                .id(10L).user(user).listing(testListing())
                .status(NotificationStatus.FAILED).errorMessage("Network error")
                .build();

        when(notificationRepository.findByStatus(NotificationStatus.FAILED)).thenReturn(java.util.List.of(failed));

        notificationService.retryFailedNotifications();

        verify(telegramBotService, never()).sendMessage(any(), any());
        verify(notificationRepository, never()).save(any());
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
    }
}
