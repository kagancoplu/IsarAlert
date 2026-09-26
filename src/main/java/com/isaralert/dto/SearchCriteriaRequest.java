package com.isaralert.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for creating or updating search criteria via the REST API.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchCriteriaRequest {

    @NotNull(message = "Telegram chat ID is required")
    private Long telegramChatId;

    @DecimalMin(value = "0.0", message = "Max rent must be positive")
    private BigDecimal maxRent;

    @DecimalMin(value = "1.0", message = "Min rooms must be at least 1")
    private BigDecimal minRooms;

    @DecimalMin(value = "1.0", message = "Max rooms must be at least 1")
    private BigDecimal maxRooms;

    @Min(value = 1, message = "Min size must be at least 1 sqm")
    private Integer minSizeSqm;

    @Min(value = 1, message = "Max size must be at least 1 sqm")
    private Integer maxSizeSqm;

    private List<String> districts;

    private List<String> ubahnLines;
}
