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
        assertThat(WgGesuchtScraper.extractDistrict(null)).isNull();
        assertThat(WgGesuchtScraper.extractDistrict("  ")).isNull();
        assertThat(WgGesuchtScraper.districtFromAddress(null)).isNull();
        assertThat(WgGesuchtScraper.districtFromAddress("Irgendwo 5, 12345 Berlin")).isNull();
    }

    // ==================== Edge cases of the page layout ====================

    private static ScrapedListingDto parse(WgGesuchtScraper scraper, String bodyHtml) {
        ScrapedListingDto.ScrapedListingDtoBuilder builder = ScrapedListingDto.builder().title("from search");
        scraper.enrichFromDetailPage(builder, Jsoup.parse("<html><head></head><body>" + bodyHtml + "</body></html>"));
        return builder.build();
    }

    @Test
    @DisplayName("search page: skips ads and partner links, keeps absolute links")
    void skipsAdsAndPartners() {
        Document doc = Jsoup.parse("""
                <a href="/wohnungen-in-Muenchen-Laim.11111111.html?asset_id=9">ad</a>
                <a href="/wohnungen-in-Muenchen-Laim.22222222.html?housinganywhere=1">partner</a>
                <a href="https://www.wg-gesucht.de/wohnungen-in-Muenchen-Laim.33333333.html">absolute</a>
                <a href="/wohnungen-in-Muenchen-Laim.33333333.html">relative</a>
                <a href="/wg-zimmer-in-Muenchen.90.0.1.0.html">not a listing</a>""");

        assertThat(scraper.extractListingHrefs(doc)).containsExactly(
                "https://www.wg-gesucht.de/wohnungen-in-Muenchen-Laim.33333333.html",
                "/wohnungen-in-Muenchen-Laim.33333333.html");
    }

    @Test
    @DisplayName("detail page: falls back to a plain <h1> and to the gallery image")
    void titleAndImageFallbacks() {
        ScrapedListingDto listing = parse(scraper, """
                <h1>Einfacher Titel</h1>
                <img class="sp-image" src="" data-src="https://img.example/lazy.jpg">""");

        assertThat(listing.getTitle()).isEqualTo("Einfacher Titel");
        assertThat(listing.getImageUrl()).isEqualTo("https://img.example/lazy.jpg");
        assertThat(parse(scraper, "<img class=\"sp-image\" src=\"https://img.example/a.jpg\">").getImageUrl())
                .isEqualTo("https://img.example/a.jpg");
        assertThat(parse(scraper, "<img class=\"sp-image\">").getImageUrl()).isNull();
    }

    @Test
    @DisplayName("detail page: blank title and description keep the search-page values")
    void blankTitleAndDescription() {
        ScrapedListingDto listing = parse(scraper, "<h1>  </h1><div id=\"ad_description_text\"> </div>");

        assertThat(listing.getTitle()).isEqualTo("from search");
        assertThat(listing.getDescription()).isNull();
    }

    @Test
    @DisplayName("availability: 'sofort' means today; impossible dates are ignored")
    void availabilityEdgeCases() {
        String row = """
                <div class="row"><span class="section_panel_detail">frei ab:</span>
                <span class="section_panel_value">%s</span></div>""";

        assertThat(parse(scraper, row.formatted("sofort")).getAvailableFrom()).isEqualTo(LocalDate.now());
        assertThat(parse(scraper, row.formatted("31.02.2026")).getAvailableFrom()).isNull();
        assertThat(parse(scraper, row.formatted("nach Absprache")).getAvailableFrom()).isNull();
        assertThat(parse(scraper, row.formatted(" ")).getAvailableFrom()).isNull();
    }

    @Test
    @DisplayName("key facts: labels without values and unknown labels are ignored")
    void incompleteKeyFacts() {
        ScrapedListingDto listing = parse(scraper, """
                <div><span class="key_fact_detail">Größe</span></div>
                <div><span class="key_fact_detail">Etage</span><b class="key_fact_value">3. OG</b></div>
                <div><span class="key_fact_detail">Zimmer</span><b class="key_fact_value">4</b></div>""");

        assertThat(listing.getSizeSqm()).isNull();
        assertThat(listing.getRooms()).isEqualByComparingTo("4");
        assertThat(listing.getPrice()).isNull();
    }

    @Test
    @DisplayName("address: missing panel, empty panel, and addresses without a postcode")
    void addressEdgeCases() {
        assertThat(parse(scraper, "<h2>Adresse</h2>").getAddress()).isNull();
        assertThat(parse(scraper, "<div><h2>Adresse</h2><span class=\"section_panel_detail\"> <br/> </span></div>")
                .getAddress()).isNull();

        ScrapedListingDto listing = parse(scraper,
                "<div><h2>Adresse</h2><span class=\"section_panel_detail\">Nahe Englischer Garten, Schwabing</span></div>");
        assertThat(listing.getAddress()).isEqualTo("Nahe Englischer Garten, Schwabing");
        assertThat(listing.getDistrict()).isEqualTo("Schwabing");

        ScrapedListingDto unknown = parse(scraper,
                "<div><h2>Adresse</h2><span class=\"section_panel_detail\">Irgendwo 1</span></div>");
        assertThat(unknown.getAddress()).isEqualTo("Irgendwo 1");
        assertThat(unknown.getDistrict()).isNull();
    }

    // ==================== Shared parsing helpers ====================

    @Test
    @DisplayName("price parsing handles German number formats and junk")
    void parsePrice() {
        assertThat(scraper.parsePrice("1.200,50 €")).isEqualByComparingTo("1200.50");
        assertThat(scraper.parsePrice("950€")).isEqualByComparingTo("950");
        assertThat(scraper.parsePrice("n.a.")).isNull();
        assertThat(scraper.parsePrice(" ")).isNull();
        assertThat(scraper.parsePrice(null)).isNull();
    }

    @Test
    @DisplayName("room parsing handles decimal commas and junk")
    void parseRooms() {
        assertThat(scraper.parseRooms("2,5 Zi.")).isEqualByComparingTo("2.5");
        assertThat(scraper.parseRooms("3 Zimmer")).isEqualByComparingTo("3");
        assertThat(scraper.parseRooms("zwei")).isNull();
        assertThat(scraper.parseRooms("")).isNull();
        assertThat(scraper.parseRooms(null)).isNull();
    }

    @Test
    @DisplayName("size parsing takes the first number and handles junk")
    void parseSize() {
        assertThat(scraper.parseSizeSqm("65 m²")).isEqualTo(65);
        assertThat(scraper.parseSizeSqm("ca. 70m2")).isEqualTo(70);
        assertThat(scraper.parseSizeSqm("99999999999 m²")).isNull();   // doesn't fit an int
        assertThat(scraper.parseSizeSqm("groß")).isNull();
        assertThat(scraper.parseSizeSqm(null)).isNull();
    }

    @Test
    @DisplayName("rate limiting sleeps between requests and survives interrupts")
    void rateLimit() {
        AppProperties props = new AppProperties();
        props.getScraper().setRequestDelayMs(20);
        WgGesuchtScraper slowScraper = new WgGesuchtScraper(props, mock(ListingRepository.class));

        long start = System.nanoTime();
        slowScraper.respectRateLimit();
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(20_000_000L);

        Thread.currentThread().interrupt();
        slowScraper.respectRateLimit();
        assertThat(Thread.interrupted()).as("interrupt flag is restored").isTrue();

        props.getScraper().setRequestDelayMs(0);
        slowScraper.respectRateLimit();   // no delay configured → returns immediately
        assertThat(slowScraper.getRandomUserAgent()).startsWith("Mozilla/5.0");
    }
}
