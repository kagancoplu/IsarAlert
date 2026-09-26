package com.isaralert.controller;

import com.isaralert.dto.ListingResponse;
import com.isaralert.exception.ResourceNotFoundException;
import com.isaralert.model.Listing;
import com.isaralert.service.ListingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for viewing scraped apartment listings.
 *
 * <ul>
 *     <li>GET /api/listings       — Paginated listing history</li>
 *     <li>GET /api/listings/{id}  — Single listing detail</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/listings")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;

    /**
     * Get a paginated list of all scraped listings, most recent first.
     */
    @GetMapping
    public ResponseEntity<Page<ListingResponse>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100)); // Cap at 100
        Page<ListingResponse> listings = listingService.findAll(pageable)
                .map(this::toResponse);

        return ResponseEntity.ok(listings);
    }

    /**
     * Get a single listing by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ListingResponse> getById(@PathVariable Long id) {
        Listing listing = listingService.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Listing", id));
        return ResponseEntity.ok(toResponse(listing));
    }

    /**
     * Delete a listing by ID. Also removes any associated notifications.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteById(@PathVariable Long id) {
        listingService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ==================== DTO Mapping ====================

    private ListingResponse toResponse(Listing listing) {
        return ListingResponse.builder()
                .id(listing.getId())
                .externalId(listing.getExternalId())
                .source(listing.getSource())
                .title(listing.getTitle())
                .description(listing.getDescription())
                .price(listing.getPrice())
                .rooms(listing.getRooms())
                .sizeSqm(listing.getSizeSqm())
                .address(listing.getAddress())
                .district(listing.getDistrict())
                .url(listing.getUrl())
                .imageUrl(listing.getImageUrl())
                .availableFrom(listing.getAvailableFrom())
                .scrapedAt(listing.getScrapedAt())
                .build();
    }
}
