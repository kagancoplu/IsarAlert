package com.isaralert.service;

import com.isaralert.dto.ScrapedListingDto;
import com.isaralert.model.Listing;
import com.isaralert.model.SearchCriteria;
import com.isaralert.model.enums.ListingSource;
import com.isaralert.repository.ListingRepository;
import com.isaralert.repository.SearchCriteriaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Core business logic for apartment listings:
 * <ul>
 *     <li>Deduplication (prevents storing the same listing twice)</li>
 *     <li>Matching (checks if a listing matches a user's criteria)</li>
 *     <li>Persistence (saves new listings to the database)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingService {

    private final ListingRepository listingRepository;
    private final SearchCriteriaRepository searchCriteriaRepository;

    /**
     * Maps Munich districts to the U-Bahn lines that serve them.
     * Used to filter listings by U-Bahn line when the scraper can extract a district.
     */
    private static final Map<String, Set<String>> DISTRICT_TO_UBAHN = Map.ofEntries(
            Map.entry("Altstadt",          Set.of("U3", "U6")),
            Map.entry("Lehel",             Set.of("U4", "U5")),
            Map.entry("Maxvorstadt",       Set.of("U2", "U3", "U6")),
            Map.entry("Schwabing",         Set.of("U3", "U6")),
            Map.entry("Haidhausen",        Set.of("U4", "U5")),
            Map.entry("Au",                Set.of("U1", "U2")),
            Map.entry("Isarvorstadt",      Set.of("U1", "U2", "U3", "U6")),
            Map.entry("Ludwigsvorstadt",   Set.of("U1", "U2")),
            Map.entry("Sendling",          Set.of("U3", "U6")),
            Map.entry("Westend",           Set.of("U4", "U5")),
            Map.entry("Schwanthalerhöhe",  Set.of("U4", "U5")),
            Map.entry("Neuhausen",         Set.of("U1")),
            Map.entry("Nymphenburg",       Set.of("U1")),
            Map.entry("Moosach",           Set.of("U2")),
            Map.entry("Milbertshofen",     Set.of("U2")),
            Map.entry("Bogenhausen",       Set.of("U4")),
            Map.entry("Berg am Laim",      Set.of("U2")),
            Map.entry("Giesing",           Set.of("U1", "U2")),
            Map.entry("Obergiesing",       Set.of("U1", "U2")),
            Map.entry("Untergiesing",      Set.of("U1", "U2")),
            Map.entry("Laim",              Set.of("U4", "U5")),
            Map.entry("Pasing",            Set.of("U4", "U5")),
            Map.entry("Perlach",           Set.of("U5")),
            Map.entry("Ramersdorf",        Set.of("U2")),
            Map.entry("Thalkirchen",       Set.of("U3")),
            Map.entry("Obersendling",      Set.of("U3")),
            Map.entry("Forstenried",       Set.of("U3")),
            Map.entry("Fürstenried",       Set.of("U3")),
            Map.entry("Solln",             Set.of("U3")),
            Map.entry("Hadern",            Set.of("U6")),
            Map.entry("Feldmoching",       Set.of("U2"))
    );

    /**
     * Processes scraped listings: deduplicates and saves new ones.
     *
     * @param scrapedListings listings from a scraper
     * @return only the newly saved listings (not previously seen)
     */
    @Transactional
    public List<Listing> processScrapedListings(List<ScrapedListingDto> scrapedListings) {
        List<Listing> newListings = new ArrayList<>();

        for (ScrapedListingDto dto : scrapedListings) {
            // Skip if we've already seen this listing
            if (listingRepository.existsByExternalIdAndSource(dto.getExternalId(), dto.getSource())) {
                log.debug("Skipping duplicate listing: {} from {}", dto.getExternalId(), dto.getSource());
                continue;
            }

            // Convert DTO to entity and save
            Listing listing = Listing.builder()
                    .externalId(dto.getExternalId())
                    .source(dto.getSource())
                    .title(dto.getTitle())
                    .description(dto.getDescription())
                    .price(dto.getPrice())
                    .rooms(dto.getRooms())
                    .sizeSqm(dto.getSizeSqm())
                    .address(dto.getAddress())
                    .district(dto.getDistrict())
                    .url(dto.getUrl())
                    .imageUrl(dto.getImageUrl())
                    .availableFrom(dto.getAvailableFrom())
                    .scrapedAt(LocalDateTime.now())
                    .build();

            listing = listingRepository.save(listing);
            newListings.add(listing);
            log.info("New listing saved: [{}] {} — €{}", dto.getSource(), dto.getTitle(), dto.getPrice());
        }

        log.info("Processed {} scraped listings → {} new, {} duplicates",
                scrapedListings.size(), newListings.size(),
                scrapedListings.size() - newListings.size());

        return newListings;
    }

    /**
     * Finds all active search criteria that match a given listing.
     *
     * <p>A listing "matches" a criteria if ALL of the following are true
     * (null criteria fields are treated as "no filter"):</p>
     * <ul>
     *     <li>listing.price ≤ criteria.maxRent</li>
     *     <li>listing.rooms ≥ criteria.minRooms AND ≤ criteria.maxRooms</li>
     *     <li>listing.sizeSqm ≥ criteria.minSizeSqm AND ≤ criteria.maxSizeSqm</li>
     *     <li>listing.district is in criteria.districts (if specified)</li>
     *     <li>listing.district maps to one of criteria.ubahnLines (if specified)</li>
     * </ul>
     */
    public List<SearchCriteria> findMatchingCriteria(Listing listing) {
        List<SearchCriteria> allActive = searchCriteriaRepository.findAllByActiveTrueAndUserActiveTrue();
        List<SearchCriteria> matched = new ArrayList<>();

        for (SearchCriteria criteria : allActive) {
            if (matchesListing(listing, criteria)) {
                matched.add(criteria);
            }
        }

        return matched;
    }

    /**
     * Checks whether a single listing satisfies a single criteria.
     */
    private boolean matchesListing(Listing listing, SearchCriteria criteria) {
        // Price check
        if (criteria.getMaxRent() != null && listing.getPrice() != null) {
            if (listing.getPrice().compareTo(criteria.getMaxRent()) > 0) {
                return false;
            }
        }

        // Room count check
        if (criteria.getMinRooms() != null && listing.getRooms() != null) {
            if (listing.getRooms().compareTo(criteria.getMinRooms()) < 0) {
                return false;
            }
        }
        if (criteria.getMaxRooms() != null && listing.getRooms() != null) {
            if (listing.getRooms().compareTo(criteria.getMaxRooms()) > 0) {
                return false;
            }
        }

        // Size check
        if (criteria.getMinSizeSqm() != null && listing.getSizeSqm() != null) {
            if (listing.getSizeSqm() < criteria.getMinSizeSqm()) {
                return false;
            }
        }
        if (criteria.getMaxSizeSqm() != null && listing.getSizeSqm() != null) {
            if (listing.getSizeSqm() > criteria.getMaxSizeSqm()) {
                return false;
            }
        }

        // District check
        if (criteria.getDistricts() != null && !criteria.getDistricts().isEmpty()
                && listing.getDistrict() != null) {
            boolean districtMatch = criteria.getDistricts().stream()
                    .anyMatch(d -> districtContains(listing.getDistrict(), d));
            if (!districtMatch) {
                return false;
            }
        }

        // U-Bahn line check
        // Only applied when the listing has a known district and the user specified lines.
        // If district is unknown, we allow the listing through (avoid false negatives).
        if (criteria.getUbahnLines() != null && !criteria.getUbahnLines().isEmpty()
                && listing.getDistrict() != null) {
            Set<String> linesForDistrict = ubahnLinesFor(listing.getDistrict());
            if (!linesForDistrict.isEmpty()) {
                boolean ubahnMatch = criteria.getUbahnLines().stream()
                        .anyMatch(line -> linesForDistrict.contains(line.toUpperCase()));
                if (!ubahnMatch) {
                    log.debug("Listing '{}' district '{}' not served by requested U-Bahn lines: {}",
                            listing.getTitle(), listing.getDistrict(), criteria.getUbahnLines());
                    return false;
                }
            } else {
                log.debug("District '{}' not in U-Bahn mapping — allowing listing through", listing.getDistrict());
            }
        }

        return true;
    }

    /**
     * Whether a listing district contains the wanted district as whole words, ignoring
     * case and hyphens. WG-Gesucht uses compound names, so "Au Haidhausen" matches both
     * "Au" and "Haidhausen". A match that only occurs inside a longer known district
     * doesn't count: "Laim" does not match "Berg am Laim".
     */
    static boolean districtContains(String listingDistrict, String wanted) {
        if (listingDistrict == null || wanted == null || wanted.isBlank()) return false;
        String listingNorm = normalizeDistrict(listingDistrict);
        String wantedNorm  = normalizeDistrict(wanted);
        if (!listingNorm.contains(wantedNorm)) return false;

        return DISTRICT_TO_UBAHN.keySet().stream()
                .map(ListingService::normalizeDistrict)
                .filter(known -> !known.equals(wantedNorm) && known.contains(wantedNorm))
                .noneMatch(listingNorm::contains);
    }

    /** Lowercases, turns hyphens into spaces and pads with spaces for whole-word matching. */
    private static String normalizeDistrict(String district) {
        return " " + district.toLowerCase(Locale.GERMAN).replace('-', ' ').trim().replaceAll("\\s+", " ") + " ";
    }

    /**
     * U-Bahn lines serving a (possibly compound) district: the union of the lines of
     * every known district it contains. Empty if none are known.
     */
    private static Set<String> ubahnLinesFor(String listingDistrict) {
        Set<String> lines = new HashSet<>();
        DISTRICT_TO_UBAHN.forEach((district, districtLines) -> {
            if (districtContains(listingDistrict, district)) {
                lines.addAll(districtLines);
            }
        });
        return lines;
    }

    // ==================== Query Methods ====================

    public Optional<Listing> findById(Long id) {
        return listingRepository.findById(id);
    }

    @Transactional
    public void deleteById(Long id) {
        if (!listingRepository.existsById(id)) {
            throw new com.isaralert.exception.ResourceNotFoundException("Listing", id);
        }
        listingRepository.deleteById(id);
    }

    public Page<Listing> findAll(Pageable pageable) {
        return listingRepository.findAllByOrderByScrapedAtDesc(pageable);
    }
}

