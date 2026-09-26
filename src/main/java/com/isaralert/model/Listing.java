package com.isaralert.model;

import com.isaralert.model.enums.ListingSource;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Represents a scraped apartment listing from a housing platform.
 *
 * <p>The combination of ({@code externalId}, {@code source}) is unique,
 * which prevents storing duplicate listings from the same platform.</p>
 */
@Entity
@Table(name = "listings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"external_id", "source"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The listing's ID on the source platform (used for deduplication).
     */
    @Column(name = "external_id", nullable = false)
    private String externalId;

    /**
     * Which platform this listing was scraped from.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 50)
    private ListingSource source;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "rooms", precision = 3, scale = 1)
    private BigDecimal rooms;

    @Column(name = "size_sqm")
    private Integer sizeSqm;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "district")
    private String district;

    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "available_from")
    private LocalDate availableFrom;

    @Column(name = "scraped_at")
    @Builder.Default
    private LocalDateTime scrapedAt = LocalDateTime.now();

    /**
     * Optimistic locking version — prevents lost-update race conditions
     * when multiple scrapers or concurrent requests modify the same listing.
     */
    @Version
    @Column(name = "version")
    private Long version;
}
