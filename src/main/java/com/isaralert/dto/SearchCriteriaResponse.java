package com.isaralert.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response body for search criteria API endpoints.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchCriteriaResponse {

    private Long id;
    private Long userId;
    private String city;
    private BigDecimal maxRent;
    private BigDecimal minRooms;
    private BigDecimal maxRooms;
    private Integer minSizeSqm;
    private Integer maxSizeSqm;
    private List<String> districts;
    private List<String> ubahnLines;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
