package com.isaralert.service.scraper;

import com.isaralert.config.AppProperties;
import com.isaralert.dto.ScrapedListingDto;
import com.isaralert.repository.ListingRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Parsing tests for {@link WgGesuchtScraper}, run against HTML fixtures that mirror
 * WG-Gesucht's markup. If WG-Gesucht changes its layout, update the fixtures in
 * {@code src/test/resources/wg-gesucht/} alongside the selectors.
 */
class WgGesuchtScraperTest {

    private final WgGesuchtScraper scraper =
            new WgGesuchtScraper(new AppProperties(), mock(ListingRepository.class));

    private static Document fixture(String name) throws IOException {
        try (InputStream in = WgGesuchtScraperTest.class.getResourceAsStream("/wg-gesucht/" + name)) {
            return Jsoup.parse(in, "UTF-8", "https://www.wg-gesucht.de");
        }
    }

    @Test
    @DisplayName("search page: extracts unique listing links and skips partner links")
    void extractsListingHrefs() throws IOException {
        List<String> hrefs = scraper.extractListingHrefs(fixture("search.html"));

        assertThat(hrefs).containsExactly(
                "/wohnungen-in-Muenchen-Au-Haidhausen.11111111.html",
                "/wohnungen-in-Muenchen-Berg-am-Laim.22222222.html",
                "/wohnungen-in-Muenchen-Schwabing-West.44444444.html");
    }

    @Test
    @DisplayName("detail page: parses all fields from the key facts and panels")
    void parsesDetailPage() throws IOException {
        ScrapedListingDto.ScrapedListingDtoBuilder builder = ScrapedListingDto.builder();

        scraper.enrichFromDetailPage(builder, fixture("detail.html"));
        ScrapedListingDto listing = builder.build();

        assertThat(listing.getTitle()).isEqualTo("Helle 2-Zimmer-Wohnung an der Isar");
        assertThat(listing.getPrice()).isEqualByComparingTo(new BigDecimal("1450")); // Gesamtmiete, not Kaltmiete
        assertThat(listing.getRooms()).isEqualByComparingTo(new BigDecimal("2.5"));
        assertThat(listing.getSizeSqm()).isEqualTo(58);
        assertThat(listing.getAddress()).isEqualTo("Musterstraße 12, 81541 München Au-Haidhausen");
        assertThat(listing.getDistrict()).isEqualTo("Au Haidhausen");
        assertThat(listing.getAvailableFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(listing.getImageUrl()).isEqualTo("https://img.wg-gesucht.de/media/up/example/main.sized.jpg");
        assertThat(listing.getDescription()).isEqualTo("Schöne, helle Wohnung mit Balkon, fünf Minuten zur U-Bahn.");
    }

    @Test
    @DisplayName("detail page: falls back to the 'Miete:' row when the key facts have no rent")
    void fallsBackToCostPanelForPrice() throws IOException {
        Document doc = fixture("detail.html");
        doc.select(".key_fact_detail:containsOwn(Gesamtmiete)").parents().first().remove();
        ScrapedListingDto.ScrapedListingDtoBuilder builder = ScrapedListingDto.builder();

        scraper.enrichFromDetailPage(builder, doc);

        assertThat(builder.build().getPrice()).isEqualByComparingTo(new BigDecimal("1200"));
    }

    @Test
    @DisplayName("detail page: leaves fields unset when the page has none of the expected markup")
    void toleratesUnknownLayout() {
        ScrapedListingDto.ScrapedListingDtoBuilder builder = ScrapedListingDto.builder().title("From search page");

        scraper.enrichFromDetailPage(builder, Jsoup.parse("<html><body><p>Nothing here</p></body></html>"));
        ScrapedListingDto listing = builder.build();

        assertThat(listing.getTitle()).isEqualTo("From search page");
        assertThat(listing.getPrice()).isNull();
        assertThat(listing.getRooms()).isNull();
        assertThat(listing.getSizeSqm()).isNull();
        assertThat(listing.getAvailableFrom()).isNull();
    }

    @Test
    @DisplayName("district: keeps compound names from the address, falls back to known districts")
    void districtFromAddress() {
        assertThat(WgGesuchtScraper.districtFromAddress("Jahnstraße 30, 80469 München Ludwigsvorstadt-Isarvorstadt"))
                .isEqualTo("Ludwigsvorstadt Isarvorstadt");
        assertThat(WgGesuchtScraper.districtFromAddress("Grafinger Straße, 81671 München Berg am Laim"))
                .isEqualTo("Berg am Laim");
        assertThat(WgGesuchtScraper.districtFromAddress("Wohnung in Schwabing, nahe Uni"))
                .isEqualTo("Schwabing");
    }

    @Test
    @DisplayName("district: matches whole words only and prefers longer names")
    void extractDistrictWholeWords() {
        assertThat(WgGesuchtScraper.extractDistrict("Augustenstraße 5, München")).isNull();
        assertThat(WgGesuchtScraper.extractDistrict("Nahe Berg am Laim")).isEqualTo("Berg am Laim");
        assertThat(WgGesuchtScraper.extractDistrict("Direkt in Laim")).isEqualTo("Laim");
    }
}
