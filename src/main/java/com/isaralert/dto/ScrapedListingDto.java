package com.isaralert.dto;

import com.isaralert.model.enums.ListingSource;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Internal DTO used to transfer data from scrapers to the service layer.
 * Not exposed via the REST API.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScrapedListingDto {

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
}
