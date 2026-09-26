package com.isaralert.repository;

import com.isaralert.model.Listing;
import com.isaralert.model.enums.ListingSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ListingRepository extends JpaRepository<Listing, Long> {

    /**
     * Check if a listing from a specific source already exists (deduplication).
     */
    boolean existsByExternalIdAndSource(String externalId, ListingSource source);

    /**
     * Paginated listing retrieval, ordered by most recent.
     */
    Page<Listing> findAllByOrderByScrapedAtDesc(Pageable pageable);

    /**
     * Find listings by source.
     */
    List<Listing> findBySource(ListingSource source);
}
