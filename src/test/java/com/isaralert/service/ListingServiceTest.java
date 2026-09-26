package com.isaralert.service;

import com.isaralert.dto.ScrapedListingDto;
import com.isaralert.model.Listing;
import com.isaralert.model.SearchCriteria;
import com.isaralert.model.User;
import com.isaralert.model.enums.ListingSource;
import com.isaralert.repository.ListingRepository;
import com.isaralert.repository.SearchCriteriaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ListingService}.
 *
 * Uses Mockito to isolate the service from the database.
 * Covers deduplication logic and the full criteria-matching matrix.
 */
@ExtendWith(MockitoExtension.class)
class ListingServiceTest {

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private SearchCriteriaRepository searchCriteriaRepository;

    @InjectMocks
    private ListingService listingService;

    // ==================== Deduplication Tests ====================

    @Nested
    @DisplayName("processScrapedListings — deduplication")
    class DeduplicationTests {

        @Test
        @DisplayName("should save new listings and skip duplicates")
        void savesNewSkipsDuplicates() {
            ScrapedListingDto newOne = dto("ext-1", "New Listing", "1200", "2", 60, "Maxvorstadt");
            ScrapedListingDto duplicate = dto("ext-2", "Dup Listing", "900", "1", 40, "Schwabing");

            when(listingRepository.existsByExternalIdAndSource("ext-1", ListingSource.WG_GESUCHT)).thenReturn(false);
            when(listingRepository.existsByExternalIdAndSource("ext-2", ListingSource.WG_GESUCHT)).thenReturn(true);
            when(listingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<Listing> result = listingService.processScrapedListings(List.of(newOne, duplicate));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getExternalId()).isEqualTo("ext-1");
            verify(listingRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("should return empty list when all listings are duplicates")
        void allDuplicates() {
            when(listingRepository.existsByExternalIdAndSource(any(), any())).thenReturn(true);
            List<Listing> result = listingService.processScrapedListings(
                    List.of(dto("dup", "Dup", "1000", "2", 50, null)));
            assertThat(result).isEmpty();
            verify(listingRepository, never()).save(any());
        }
    }

    // ==================== Matching Tests ====================

    @Nested
    @DisplayName("findMatchingCriteria — filter matching")
    class MatchingTests {

        private SearchCriteria criteria;
        private Listing listing;

        @BeforeEach
        void setUp() {
            User user = User.builder().id(1L).telegramChatId(123L).active(true).build();
            criteria = SearchCriteria.builder()
                    .id(1L)
                    .user(user)
                    .maxRent(new BigDecimal("1500"))
                    .minRooms(new BigDecimal("2"))
                    .maxRooms(new BigDecimal("4"))
                    .minSizeSqm(50)
                    .maxSizeSqm(100)
                    .active(true)
                    .build();
            listing = buildListing("1200", "2", 70, "Maxvorstadt");
            when(searchCriteriaRepository.findAllByActiveTrueAndUserActiveTrue()).thenReturn(List.of(criteria));
        }

        @Test
        @DisplayName("should match a listing that satisfies all criteria")
        void matchesAllCriteria() {
            assertThat(listingService.findMatchingCriteria(listing)).hasSize(1);
        }

        @Test
        @DisplayName("should not match when price exceeds maxRent")
        void rejectsHighPrice() {
            listing.setPrice(new BigDecimal("1800"));
            assertThat(listingService.findMatchingCriteria(listing)).isEmpty();
        }

        @Test
        @DisplayName("should not match when rooms below minRooms")
        void rejectsTooFewRooms() {
            listing.setRooms(new BigDecimal("1"));
            assertThat(listingService.findMatchingCriteria(listing)).isEmpty();
        }

        @Test
        @DisplayName("should not match when size below minSizeSqm")
        void rejectsTooSmall() {
            listing.setSizeSqm(30);
            assertThat(listingService.findMatchingCriteria(listing)).isEmpty();
        }

        @Test
        @DisplayName("should not match too many rooms or too large an apartment")
        void rejectsAboveMaximums() {
            listing.setRooms(new BigDecimal("5"));
            assertThat(listingService.findMatchingCriteria(listing)).isEmpty();
            listing.setRooms(new BigDecimal("3"));
            listing.setSizeSqm(120);
            assertThat(listingService.findMatchingCriteria(listing)).isEmpty();
        }

        @Test
        @DisplayName("listings without a district pass district and U-Bahn filters")
        void unknownDistrictPasses() {
            criteria.setDistricts(List.of("Schwabing"));
            criteria.setUbahnLines(List.of("U3"));
            listing.setDistrict(null);
            assertThat(listingService.findMatchingCriteria(listing)).hasSize(1);
        }

        @Test
        @DisplayName("empty district and U-Bahn lists mean no filter")
        void emptyListsMeanNoFilter() {
            criteria.setDistricts(List.of());
            criteria.setUbahnLines(List.of());
            listing.setDistrict("Perlach");
            assertThat(listingService.findMatchingCriteria(listing)).hasSize(1);
        }

        @Test
        @DisplayName("should match when district is in allowed list")
        void matchesAllowedDistrict() {
            criteria.setDistricts(List.of("Maxvorstadt", "Schwabing"));
            listing.setDistrict("Maxvorstadt");
            assertThat(listingService.findMatchingCriteria(listing)).hasSize(1);
        }

        @Test
        @DisplayName("should not match when district is not in allowed list")
        void rejectsDisallowedDistrict() {
            criteria.setDistricts(List.of("Schwabing"));
            listing.setDistrict("Perlach");
            assertThat(listingService.findMatchingCriteria(listing)).isEmpty();
        }

        @Test
        @DisplayName("should match either half of a compound WG-Gesucht district")
        void matchesCompoundDistrict() {
            listing.setDistrict("Au Haidhausen");
            criteria.setDistricts(List.of("haidhausen"));
            assertThat(listingService.findMatchingCriteria(listing)).hasSize(1);
            criteria.setDistricts(List.of("Au"));
            assertThat(listingService.findMatchingCriteria(listing)).hasSize(1);
        }

        @Test
        @DisplayName("should not match a district that only appears inside a longer district name")
        void rejectsDistrictInsideLongerName() {
            criteria.setDistricts(List.of("Laim"));
            listing.setDistrict("Berg am Laim");
            assertThat(listingService.findMatchingCriteria(listing)).isEmpty();
            listing.setDistrict("Laim");
            assertThat(listingService.findMatchingCriteria(listing)).hasSize(1);
        }

        @Test
        @DisplayName("should combine U-Bahn lines of every part of a compound district")
        void matchesUBahnLineOfCompoundDistrict() {
            criteria.setUbahnLines(List.of("U4"));
            listing.setDistrict("Au Haidhausen"); // Au: U1, U2 — Haidhausen: U4, U5
            assertThat(listingService.findMatchingCriteria(listing)).hasSize(1);
        }

        @Test
        @DisplayName("should match when U-Bahn line serves the listing district")
        void matchesUBahnLine() {
            criteria.setUbahnLines(List.of("U2", "U6"));
            listing.setDistrict("Maxvorstadt"); // served by U2, U3, U6
            assertThat(listingService.findMatchingCriteria(listing)).hasSize(1);
        }

        @Test
        @DisplayName("should not match when U-Bahn line does not serve the district")
        void rejectsWrongUBahnLine() {
            criteria.setUbahnLines(List.of("U5"));
            listing.setDistrict("Maxvorstadt"); // served by U2, U3, U6 — NOT U5
            assertThat(listingService.findMatchingCriteria(listing)).isEmpty();
        }

        @Test
        @DisplayName("should allow listing through when district not in U-Bahn mapping")
        void allowsUnknownDistrictWhenUBahnFilterSet() {
            criteria.setUbahnLines(List.of("U5"));
            listing.setDistrict("Untermenzing"); // not in mapping — pass through
            assertThat(listingService.findMatchingCriteria(listing)).hasSize(1);
        }

        @Test
        @DisplayName("null criteria fields should act as no filter")
        void nullCriteriaFieldsMeanNoFilter() {
            criteria.setMaxRent(null);
            criteria.setMinRooms(null);
            criteria.setMaxRooms(null);
            criteria.setMinSizeSqm(null);
            criteria.setMaxSizeSqm(null);
            listing.setPrice(new BigDecimal("9999"));
            assertThat(listingService.findMatchingCriteria(listing)).hasSize(1);
        }
    }

    @Test
    @DisplayName("districtContains handles missing values")
    void districtContainsNulls() {
        assertThat(ListingService.districtContains(null, "Laim")).isFalse();
        assertThat(ListingService.districtContains("Laim", null)).isFalse();
        assertThat(ListingService.districtContains("Laim", " ")).isFalse();
        assertThat(ListingService.districtContains("Berg-am-Laim", "berg am laim")).isTrue();
    }

    // ==================== Helpers ====================

    private ScrapedListingDto dto(String extId, String title, String price, String rooms, int size, String district) {
        return ScrapedListingDto.builder()
                .externalId(extId)
                .source(ListingSource.WG_GESUCHT)
                .title(title)
                .price(new BigDecimal(price))
                .rooms(new BigDecimal(rooms))
                .sizeSqm(size)
                .district(district)
                .url("https://wg-gesucht.de/" + extId)
                .build();
    }

    private Listing buildListing(String price, String rooms, int size, String district) {
        return Listing.builder()
                .id(1L)
                .externalId("ext-test")
                .source(ListingSource.WG_GESUCHT)
                .title("Test Listing")
                .price(new BigDecimal(price))
                .rooms(new BigDecimal(rooms))
                .sizeSqm(size)
                .district(district)
                .url("https://wg-gesucht.de/test")
                .build();
    }
}
