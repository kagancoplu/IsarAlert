package com.isaralert.repository;

import com.isaralert.model.Notification;
import com.isaralert.model.enums.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Check if a user has already been notified about a specific listing.
     */
    boolean existsByUserIdAndListingId(Long userId, Long listingId);

    /**
     * Find notifications by status (e.g., retry FAILED ones).
     */
    List<Notification> findByStatus(NotificationStatus status);

    /**
     * Get all notifications for a user.
     */
    List<Notification> findByUserId(Long userId);
}
