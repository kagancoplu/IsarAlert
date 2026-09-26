package com.isaralert.service;

import com.isaralert.model.Listing;
import com.isaralert.model.Notification;
import com.isaralert.model.User;
import com.isaralert.model.enums.NotificationStatus;
import com.isaralert.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Manages the notification lifecycle:
 * <ol>
 *     <li>Creates a PENDING notification record</li>
 *     <li>Delegates to {@link TelegramBotService} for delivery</li>
 *     <li>Updates status to SENT or FAILED</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** Maximum number of delivery attempts before giving up on a failed notification. */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final NotificationRepository notificationRepository;
    private final TelegramMessageSender telegramBotService;

    /**
     * Sends a notification to a user about a matching listing.
     * Skips if the user has already been notified about this listing.
     */
    @Transactional
    public void notifyUser(User user, Listing listing) {
        // Check if already notified
        if (notificationRepository.existsByUserIdAndListingId(user.getId(), listing.getId())) {
            log.debug("User {} already notified about listing {}", user.getId(), listing.getId());
            return;
        }

        // Create notification record
        Notification notification = Notification.builder()
                .user(user)
                .listing(listing)
                .status(NotificationStatus.PENDING)
                .build();
        notification = notificationRepository.save(notification);

        // Attempt delivery
        try {
            String message = formatListingMessage(listing);
            telegramBotService.sendMessage(user.getTelegramChatId(), message);

            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now());
            log.info("✅ Notification sent to user {} (chat {}) for listing: {}",
                    user.getId(), user.getTelegramChatId(), listing.getTitle());
        } catch (Exception e) {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setErrorMessage(e.getMessage());
            log.error("❌ Failed to send notification to user {} for listing {}: {}",
                    user.getId(), listing.getId(), e.getMessage());
        }

        notificationRepository.save(notification);
    }

    /**
     * Retries all FAILED notifications up to {@link #MAX_RETRY_ATTEMPTS} times.
     *
     * <p>Called periodically by the scheduler after each main scan cycle.
     * Notifications that have already been retried too many times are marked
     * with a permanent failure prefix in their error message and skipped.</p>
     */
    @Transactional
    public void retryFailedNotifications() {
        List<Notification> failed = notificationRepository.findByStatus(NotificationStatus.FAILED);
        if (failed.isEmpty()) {
            return;
        }

        log.info("Retrying {} failed notification(s)", failed.size());

        for (Notification notification : failed) {
            // Don't deliver to users who paused notifications via /stop
            if (!Boolean.TRUE.equals(notification.getUser().getActive())) {
                continue;
            }

            // Count previous retry attempts embedded in the error message prefix
            int retryCount = countRetryAttempts(notification.getErrorMessage());
            if (retryCount >= MAX_RETRY_ATTEMPTS) {
                log.warn("Notification id={} for user={} has exceeded max retries — giving up",
                        notification.getId(), notification.getUser().getId());
                continue;
            }

            try {
                String message = formatListingMessage(notification.getListing());
                telegramBotService.sendMessage(notification.getUser().getTelegramChatId(), message);

                notification.setStatus(NotificationStatus.SENT);
                notification.setSentAt(LocalDateTime.now());
                notification.setErrorMessage(null);
                log.info("✅ Retry succeeded for notification id={} (attempt {})",
                        notification.getId(), retryCount + 1);
            } catch (Exception e) {
                // Prefix the error message with a retry counter
                notification.setErrorMessage("[retry:" + (retryCount + 1) + "] " + e.getMessage());
                log.warn("Retry {} failed for notification id={}: {}",
                        retryCount + 1, notification.getId(), e.getMessage());
            }

            notificationRepository.save(notification);
        }
    }

    /**
     * Counts how many retry attempts are recorded in the error message prefix.
     * Format: {@code [retry:N] original message}
     */
    private int countRetryAttempts(String errorMessage) {
        if (errorMessage == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\[retry:(\\d+)\\]")
                .matcher(errorMessage);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * Formats a listing into a MarkdownV2 Telegram message with emoji.
     */
    private String formatListingMessage(Listing listing) {
        StringBuilder sb = new StringBuilder();

        sb.append("🏠 *New Apartment Found\\!*\n\n");
        sb.append("📌 *").append(escape(listing.getTitle())).append("*\n\n");

        if (listing.getPrice() != null) {
            sb.append("💰 Price: €").append(escape(listing.getPrice().toPlainString())).append("/month\n");
        }
        if (listing.getRooms() != null) {
            sb.append("🚪 Rooms: ").append(escape(listing.getRooms().toPlainString())).append("\n");
        }
        if (listing.getSizeSqm() != null) {
            sb.append("📐 Size: ").append(listing.getSizeSqm()).append(" m²\n");
        }
        if (listing.getAddress() != null) {
            sb.append("📍 Location: ").append(escape(listing.getAddress())).append("\n");
        }
        if (listing.getDistrict() != null) {
            sb.append("🏘️ District: ").append(escape(listing.getDistrict())).append("\n");
        }
        if (listing.getAvailableFrom() != null) {
            sb.append("📅 Available from: ").append(escape(listing.getAvailableFrom().toString())).append("\n");
        }

        sb.append("\n🔗 Source: ").append(escape(listing.getSource().name())).append("\n");
        sb.append("👉 [View Listing](").append(listing.getUrl()).append(")\n");
        sb.append("\n_Sent by IsarAlert 🏔️_");

        return sb.toString();
    }

    /**
     * Escapes special characters for Telegram MarkdownV2 formatting.
     */
    private String escape(String text) {
        if (text == null) return "";
        return text.replaceAll("([_*\\[\\]()~`>#+\\-=|{}.!\\\\])", "\\\\$1");
    }
}
