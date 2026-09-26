package com.isaralert.service.scraper;

import com.isaralert.dto.ScrapedListingDto;
import com.isaralert.model.SearchCriteria;
import com.isaralert.model.enums.ListingSource;

import java.util.List;

/**
 * Strategy interface for housing platform scrapers.
 *
 * <p>Each supported platform implements this interface. The
 * {@link com.isaralert.service.SchedulerService} discovers all implementations
 * via Spring's dependency injection and iterates over them during each scan cycle.</p>
 *
 * <p>To add a new platform, simply create a new {@code @Service} class
 * implementing this interface — no other changes needed.</p>
 */
public interface ListingScraper {

    /**
     * Returns which platform this scraper targets.
     */
    ListingSource getSource();

    /**
     * Scrapes listings matching the given criteria from this platform.
     *
     * @param criteria the user's search filters
     * @return list of scraped listings (may be empty, never null)
     */
    List<ScrapedListingDto> scrape(SearchCriteria criteria);
}
