package com.isaralert.dto;

import lombok.*;

import java.time.LocalDateTime;

/**
 * Response body for user API endpoints.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponse {

    private Long id;
    private Long telegramChatId;
    private String username;
    private String firstName;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
