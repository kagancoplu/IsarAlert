package com.isaralert.controller;

import com.isaralert.dto.SearchCriteriaRequest;
import com.isaralert.dto.SearchCriteriaResponse;
import com.isaralert.service.SearchCriteriaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for managing apartment search criteria.
 *
 * <ul>
 *     <li>POST   /api/criteria            — Create new criteria</li>
 *     <li>GET    /api/criteria/user/{userId} — Get all criteria for a user</li>
 *     <li>PUT    /api/criteria/{id}        — Update criteria</li>
 *     <li>DELETE /api/criteria/{id}        — Delete criteria</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/criteria")
@RequiredArgsConstructor
public class SearchCriteriaController {

    private final SearchCriteriaService searchCriteriaService;

    /**
     * Create new search criteria for a user identified by Telegram chat ID.
     * The user is auto-created if they don't exist yet.
     */
    @PostMapping
    public ResponseEntity<SearchCriteriaResponse> create(@Valid @RequestBody SearchCriteriaRequest request) {
        SearchCriteriaResponse response = searchCriteriaService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get all search criteria for a specific user.
     * Returns an empty list (200 OK) if the user has no criteria — not a 404.
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<SearchCriteriaResponse>> getByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(searchCriteriaService.findByUserId(userId));
    }

    /**
     * Update existing search criteria (partial update — null fields are ignored).
     */
    @PutMapping("/{id}")
    public ResponseEntity<SearchCriteriaResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody SearchCriteriaRequest request) {
        return ResponseEntity.ok(searchCriteriaService.update(id, request));
    }

    /**
     * Delete search criteria by ID.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        searchCriteriaService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
