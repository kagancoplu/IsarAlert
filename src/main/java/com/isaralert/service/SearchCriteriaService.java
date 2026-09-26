package com.isaralert.service;

import com.isaralert.dto.SearchCriteriaRequest;
import com.isaralert.dto.SearchCriteriaResponse;
import com.isaralert.exception.ResourceNotFoundException;
import com.isaralert.model.SearchCriteria;
import com.isaralert.model.User;
import com.isaralert.repository.SearchCriteriaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Business logic for managing user search criteria.
 *
 * <p>
 * Extracted from {@link com.isaralert.controller.SearchCriteriaController}
 * to enforce the service layer boundary and ensure {@code @PreUpdate} fires
 * reliably within a single managed transaction.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchCriteriaService {

    private final SearchCriteriaRepository searchCriteriaRepository;
    private final UserService userService;

    /**
     * Creates new search criteria for a user identified by their Telegram chat ID.
     * The user is auto-created if they don't exist yet.
     */
    @Transactional
    public SearchCriteriaResponse create(SearchCriteriaRequest request) {
        User user = userService.createOrGetUser(request.getTelegramChatId(), null, null);

        SearchCriteria criteria = SearchCriteria.builder()
                .user(user)
                .maxRent(request.getMaxRent())
                .minRooms(request.getMinRooms())
                .maxRooms(request.getMaxRooms())
                .minSizeSqm(request.getMinSizeSqm())
                .maxSizeSqm(request.getMaxSizeSqm())
                .districts(request.getDistricts())
                .ubahnLines(request.getUbahnLines())
                .active(true)
                .build();

        criteria = searchCriteriaRepository.save(criteria);
        log.info("Created search criteria id={} for user id={}", criteria.getId(), user.getId());
        return toResponse(criteria);
    }

    /**
     * Returns all search criteria for a given user.
     * Returns an empty list (not a 404) when the user has no criteria.
     */
    @Transactional(readOnly = true)
    public List<SearchCriteriaResponse> findByUserId(Long userId) {
        return searchCriteriaRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Updates an existing criteria. Only non-null request fields overwrite existing
     * values.
     * Runs inside a transaction so {@code @PreUpdate} fires correctly.
     */
    @Transactional
    public SearchCriteriaResponse update(Long id, SearchCriteriaRequest request) {
        SearchCriteria criteria = searchCriteriaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SearchCriteria", id));

        if (request.getMaxRent() != null)
            criteria.setMaxRent(request.getMaxRent());
        if (request.getMinRooms() != null)
            criteria.setMinRooms(request.getMinRooms());
        if (request.getMaxRooms() != null)
            criteria.setMaxRooms(request.getMaxRooms());
        if (request.getMinSizeSqm() != null)
            criteria.setMinSizeSqm(request.getMinSizeSqm());
        if (request.getMaxSizeSqm() != null)
            criteria.setMaxSizeSqm(request.getMaxSizeSqm());
        if (request.getDistricts() != null)
            criteria.setDistricts(request.getDistricts());
        if (request.getUbahnLines() != null)
            criteria.setUbahnLines(request.getUbahnLines());

        // No explicit save needed — entity is managed within this transaction.
        // @PreUpdate will fire automatically on flush.
        criteria = searchCriteriaRepository.save(criteria);
        log.info("Updated search criteria id={}", id);
        return toResponse(criteria);
    }

    /**
     * Deletes a search criteria by ID.
     */
    @Transactional
    public void delete(Long id) {
        if (!searchCriteriaRepository.existsById(id)) {
            throw new ResourceNotFoundException("SearchCriteria", id);
        }
        searchCriteriaRepository.deleteById(id);
        log.info("Deleted search criteria id={}", id);
    }

    // ==================== DTO Mapping ====================

    public SearchCriteriaResponse toResponse(SearchCriteria criteria) {
        return SearchCriteriaResponse.builder()
                .id(criteria.getId())
                .userId(criteria.getUser().getId())
                .city(criteria.getCity())
                .maxRent(criteria.getMaxRent())
                .minRooms(criteria.getMinRooms())
                .maxRooms(criteria.getMaxRooms())
                .minSizeSqm(criteria.getMinSizeSqm())
                .maxSizeSqm(criteria.getMaxSizeSqm())
                .districts(criteria.getDistricts())
                .ubahnLines(criteria.getUbahnLines())
                .active(criteria.getActive())
                .createdAt(criteria.getCreatedAt())
                .updatedAt(criteria.getUpdatedAt())
                .build();
    }
}
