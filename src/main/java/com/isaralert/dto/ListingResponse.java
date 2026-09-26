package com.isaralert.dto;

import com.isaralert.model.enums.ListingSource;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response body for listing API endpoints.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ListingResponse {

    private Long id;
    private String externalId;
    private ListingSource source;
    private String title;
    private String description;
    private BigDecimal price;
    private BigDecimal rooms;
    private Integer sizeSqm;
    private String address;
    private String district;
    private String url;
    private String imageUrl;
    private LocalDate availableFrom;
    private LocalDateTime scrapedAt;
}
