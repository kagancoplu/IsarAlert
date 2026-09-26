package com.isaralert.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Defines what kind of apartments a user is looking for.
 * Each user can have multiple criteria (e.g., different price/area combos).
 *
 * <p>The {@code districts} and {@code ubahnLines} fields use PostgreSQL TEXT[] arrays
 * to allow flexible multi-value filtering without additional join tables.</p>
 */
@Entity
@Table(name = "search_criteria")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchCriteria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "city")
    @Builder.Default
    private String city = "München";

    @Column(name = "max_rent", precision = 10, scale = 2)
    private BigDecimal maxRent;

    @Column(name = "min_rooms", precision = 3, scale = 1)
    private BigDecimal minRooms;

    @Column(name = "max_rooms", precision = 3, scale = 1)
    private BigDecimal maxRooms;

    @Column(name = "min_size_sqm")
    private Integer minSizeSqm;

    @Column(name = "max_size_sqm")
    private Integer maxSizeSqm;

    /**
     * Preferred Munich districts, e.g. ["Maxvorstadt", "Schwabing", "Sendling"].
     * Stored as PostgreSQL TEXT[] array.
     */
    @Column(name = "districts", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> districts;

    /**
     * Preferred U-Bahn lines, e.g. ["U3", "U6"].
     * Stored as PostgreSQL TEXT[] array.
     */
    @Column(name = "ubahn_lines", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> ubahnLines;

    @Column(name = "active")
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
