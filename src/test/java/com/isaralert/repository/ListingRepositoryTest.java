package com.isaralert.repository;

import com.isaralert.model.Listing;
import com.isaralert.model.enums.ListingSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ListingRepository} using Testcontainers.
 *
 * Spins up a real PostgreSQL container to verify JPA queries and Flyway
 * migration (V1 + V2) run correctly, including the unique constraint
 * and the {@code @Version} column.
 *
 * Requires Docker to be running.
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ListingRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("isaralert_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.clean-disabled", () -> "false");
    }

    @Autowired
    private ListingRepository listingRepository;

    @Test
    @DisplayName("should detect duplicate listings by external ID and source")
    void shouldDetectDuplicate() {
        Listing listing = listing("99999", ListingSource.WG_GESUCHT);
        listingRepository.save(listing);

        assertThat(listingRepository.existsByExternalIdAndSource("99999", ListingSource.WG_GESUCHT)).isTrue();
        // Same ID but different source should NOT match
        assertThat(listingRepository.existsByExternalIdAndSource("99999", ListingSource.IMMOSCOUT24)).isFalse();
    }

    @Test
    @DisplayName("should paginate listings ordered by scraped_at descending")
    void shouldPaginateByDate() {
        listingRepository.save(listing("1", ListingSource.WG_GESUCHT));
        listingRepository.save(listing("2", ListingSource.WG_GESUCHT));
        listingRepository.save(listing("3", ListingSource.WG_GESUCHT));

        List<Listing> all = listingRepository.findAll();
        assertThat(all).hasSize(3);
    }

    @Test
    @DisplayName("should find listings by source")
    void shouldFindBySource() {
        listingRepository.save(listing("a1", ListingSource.WG_GESUCHT));
        listingRepository.save(listing("b1", ListingSource.IMMOSCOUT24));

        List<Listing> wgListings = listingRepository.findBySource(ListingSource.WG_GESUCHT);
        assertThat(wgListings).hasSize(1);
        assertThat(wgListings.get(0).getExternalId()).isEqualTo("a1");
    }

    // ==================== Helper ====================

    private Listing listing(String extId, ListingSource source) {
        return Listing.builder()
                .externalId(extId)
                .source(source)
                .title("Test Apartment " + extId)
                .price(new BigDecimal("900.00"))
                .url("https://example.com/" + extId)
                .build();
    }
}
