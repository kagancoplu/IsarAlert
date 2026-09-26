package com.isaralert.controller;

import com.isaralert.dto.UserResponse;
import com.isaralert.exception.ResourceNotFoundException;
import com.isaralert.model.User;
import com.isaralert.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST API for querying registered Telegram users.
 *
 * <ul>
 *     <li>GET /api/users                     — List all active users</li>
 *     <li>GET /api/users/{id}                — Get user by internal DB ID</li>
 *     <li>GET /api/users/by-chat/{chatId}    — Get user by Telegram chat ID</li>
 *     <li>POST /api/users/{id}/deactivate    — Deactivate a user</li>
 *     <li>POST /api/users/{id}/activate      — Activate a user</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * List all active users.
     */
    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllActive() {
        List<UserResponse> users = userService.findAllActive()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    /**
     * Get a user by their internal database ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getById(@PathVariable Long id) {
        User user = userService.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        return ResponseEntity.ok(toResponse(user));
    }

    /**
     * Get a user by their Telegram chat ID.
     * This is the primary lookup endpoint — Telegram chat IDs are the public-facing identifier.
     */
    @GetMapping("/by-chat/{chatId}")
    public ResponseEntity<UserResponse> getByChatId(@PathVariable Long chatId) {
        User user = userService.findByTelegramChatId(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("User with chatId", chatId));
        return ResponseEntity.ok(toResponse(user));
    }

    /**
     * Deactivate a user — they will stop receiving notifications.
     */
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<UserResponse> deactivate(@PathVariable Long id) {
        User user = userService.deactivateUser(id);
        log.info("User id={} deactivated via API", id);
        return ResponseEntity.ok(toResponse(user));
    }

    /**
     * Reactivate a previously deactivated user.
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<UserResponse> activate(@PathVariable Long id) {
        User user = userService.activateUser(id);
        log.info("User id={} activated via API", id);
        return ResponseEntity.ok(toResponse(user));
    }

    // ==================== DTO Mapping ====================

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .telegramChatId(user.getTelegramChatId())
                .username(user.getUsername())
                .firstName(user.getFirstName())
                .active(user.getActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
