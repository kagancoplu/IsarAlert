package com.isaralert.service.scraper;

import com.isaralert.config.AppProperties;
import com.isaralert.dto.ScrapedListingDto;
import com.isaralert.exception.ScrapingException;
import com.isaralert.model.SearchCriteria;
import com.isaralert.model.enums.ListingSource;
import com.isaralert.repository.ListingRepository;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Scraper implementation for WG-Gesucht (wg-gesucht.de).
 *
 * <p>WG-Gesucht is one of the most popular platforms for finding apartments
 * and shared flats (WGs) in Germany, especially in university cities like Munich.</p>
 *
 * <h3>URL Structure</h3>
 * <pre>
 * Base: https://www.wg-gesucht.de/
 * Apartments in Munich: https://www.wg-gesucht.de/wohnungen-in-Muenchen.90.2.1.0.html
 *
 * URL components:
 *   - "wohnungen" = apartment type (wg-zimmer=WG, wohnungen=apartments, haeuser=houses)
 *   - "Muenchen" = city name
 *   - "90"       = city code for Munich
 *   - "2"        = listing type (0=WG, 1=1-room, 2=apartment, 3=house)
 *   - "1"        = offer type (0=any, 1=rent)
 *   - "0"        = page number
 * </pre>
 *
 * <h3>Important Notes</h3>
 * <ul>
 *     <li>CSS selectors may change when WG-Gesucht updates their site.
 *         If scraping breaks, inspect the live site and update the selectors below.</li>
 *     <li>WG-Gesucht uses anti-bot measures. The rate limiter in
 *         {@link AbstractListingScraper} helps avoid detection.</li>
 *     <li>This scraper is for <b>personal, educational use only</b>.</li>
 * </ul>
 */
@Slf4j
@Service
public class WgGesuchtScraper extends AbstractListingScraper {

    private static final String BASE_URL = "https://www.wg-gesucht.de";
    private static final int MUNICH_CITY_CODE = 90;
    private static final int TYPE_APARTMENT = 2;
    private static final int OFFER_RENT = 1;
    private static final int MAX_PAGES = 3;

    // German date format used on WG-Gesucht: "01.06.2025"
    private static final DateTimeFormatter WG_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    // Compiled patterns reused across calls
    private static final Pattern EXTERNAL_ID_PATTERN = Pattern.compile("\\.(\\d{4,})\\.html");
    private static final Pattern DISTRICT_PATTERN    = Pattern.compile("/wohnungen-in-Muenchen-([^.]+)\\.");
    private static final Pattern DATE_PATTERN        = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4})");
    // District after the postcode in the address: "81543 München Au-Haidhausen" → "Au-Haidhausen"
    private static final Pattern ADDRESS_DISTRICT_PATTERN = Pattern.compile("\\d{5}\\s+München\\s+(.+)$");

    // Known Munich districts used for address-based district extraction
    private static final List<String> KNOWN_DISTRICTS = List.of(
            "Altstadt", "Lehel", "Ludwigsvorstadt", "Isarvorstadt",
            "Maxvorstadt", "Schwabing", "Au", "Haidhausen",
            "Sendling", "Westend", "Schwanthalerhöhe", "Neuhausen",
            "Nymphenburg", "Moosach", "Milbertshofen", "Am Hart",
            "Bogenhausen", "Berg am Laim", "Trudering", "Riem",
            "Giesing", "Obergiesing", "Untergiesing", "Laim",
            "Pasing", "Obermenzing", "Allach", "Untermenzing",
            "Feldmoching", "Hasenbergl", "Thalkirchen", "Obersendling",
            "Forstenried", "Fürstenried", "Solln", "Hadern",
            "Perlach", "Ramersdorf"
    );

    // Longest names first, so "Berg am Laim" wins over "Laim"
    private static final List<String> MUNICH_DISTRICTS = KNOWN_DISTRICTS.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    private final ListingRepository listingRepository;

    public WgGesuchtScraper(AppProperties appProperties, ListingRepository listingRepository) {
        super(appProperties);
        this.listingRepository = listingRepository;
    }

    @Override
    public ListingSource getSource() {
        return ListingSource.WG_GESUCHT;
    }

    // ==================== Entry Point ====================

    @Override
    public List<ScrapedListingDto> scrape(SearchCriteria criteria) {
        log.info("Starting WG-Gesucht scrape: maxRent={}, rooms={}-{}, size={}-{} sqm",
                criteria.getMaxRent(), criteria.getMinRooms(), criteria.getMaxRooms(),
                criteria.getMinSizeSqm(), criteria.getMaxSizeSqm());

        List<ScrapedListingDto> allListings = new ArrayList<>();

        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                String url = buildSearchUrl(criteria, page);
                log.debug("Scraping search page {}: {}", page, url);

                Document doc = createConnection(url).referrer(BASE_URL).get();
                List<ScrapedListingDto> pageListings = parseListingsPage(doc);

                if (pageListings.isEmpty()) {
                    log.debug("No listings on page {} — stopping pagination", page);
                    break;
                }

                allListings.addAll(pageListings);
                log.debug("Found {} listings on page {}", pageListings.size(), page);

                if (page < MAX_PAGES - 1) {
                    respectRateLimit();
                }
            }
        } catch (Exception e) {
            throw new ScrapingException("WG_GESUCHT", "Failed to scrape listings: " + e.getMessage(), e);
        }

        log.info("WG-Gesucht scrape complete: {} total listings", allListings.size());
        return allListings;
    }

    // ==================== URL Construction ====================

    private String buildSearchUrl(SearchCriteria criteria, int page) {
        StringBuilder url = new StringBuilder();
        url.append(BASE_URL)
                .append("/wohnungen-in-Muenchen.")
                .append(MUNICH_CITY_CODE).append(".")
                .append(TYPE_APARTMENT).append(".")
                .append(OFFER_RENT).append(".")
                .append(page)
                .append(".html");

        StringBuilder params = new StringBuilder();
        if (criteria.getMaxRent() != null)   params.append("&rMax=").append(criteria.getMaxRent().intValue());
        if (criteria.getMinSizeSqm() != null) params.append("&sMin=").append(criteria.getMinSizeSqm());
        if (criteria.getMaxSizeSqm() != null) params.append("&sMax=").append(criteria.getMaxSizeSqm());

        if (!params.isEmpty()) {
            url.append("?").append(params.substring(1));
        }
        return url.toString();
    }

    // ==================== Search Results Parsing ====================

    /**
     * Collects unique listing hrefs from a search results page, then fetches
     * each listing's detail page to populate price, rooms, size, and other fields.
     */
    private List<ScrapedListingDto> parseListingsPage(Document doc) {
        List<ScrapedListingDto> listings = new ArrayList<>();

        List<String> hrefs = extractListingHrefs(doc);
        log.info("WG-Gesucht: {} unique apartment listings found on page", hrefs.size());

        for (String href : hrefs) {
            try {
                ScrapedListingDto listing = buildListing(doc, href);
                if (listing != null) {
                    listings.add(listing);
                }
            } catch (Exception e) {
                log.warn("Failed to build listing from href {}: {}", href, e.getMessage());
            }
        }

        return listings;
    }

    /**
     * Returns the unique Munich apartment detail-page hrefs on a search results page,
     * in page order, excluding sponsored/partner links.
     */
    List<String> extractListingHrefs(Document doc) {
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : doc.select("a[href~=/wohnungen-in-Muenchen[^.]*\\.\\d{4,}\\.html]")) {
            String href = link.attr("href");
            if (href.contains("asset_id") || href.contains("utm_source") || href.contains("housinganywhere")) {
                continue;
            }
            seen.add(href);
        }
        return new ArrayList<>(seen);
    }

    /**
     * Builds a fully-populated listing DTO by combining data from the search
     * results page (externalId, district, title, url) with the detail page
     * (price, rooms, sizeSqm, description, address, imageUrl, availableFrom).
     */
    private ScrapedListingDto buildListing(Document searchDoc, String href) {
        String fullUrl = href.startsWith("http") ? href : BASE_URL + href;

        // External ID from URL numeric segment
        Matcher idMatcher = EXTERNAL_ID_PATTERN.matcher(href);
        if (!idMatcher.find()) return null;
        String externalId = idMatcher.group(1);

        // Skip detail page fetch for already-known listings — avoids 3s delay per duplicate
        if (listingRepository.existsByExternalIdAndSource(externalId, ListingSource.WG_GESUCHT)) {
            log.debug("Skipping known listing {}", externalId);
            return null;
        }

        // District from URL path: /wohnungen-in-Muenchen-Au-Haidhausen.123.html → "Au Haidhausen"
        String district = null;
        Matcher districtMatcher = DISTRICT_PATTERN.matcher(href);
        if (districtMatcher.find()) {
            district = districtMatcher.group(1).replace("-", " ").trim();
        }

        // Coarse title from search page anchor text
        Element linkEl = searchDoc.selectFirst("a[href=" + href + "]");
        String title = (linkEl != null && !linkEl.text().isBlank()) ? linkEl.text().trim() : "München Apartment";

        ScrapedListingDto.ScrapedListingDtoBuilder builder = ScrapedListingDto.builder()
                .externalId(externalId)
                .source(ListingSource.WG_GESUCHT)
                .title(title)
                .district(district)
                .address(district != null ? "München, " + district : "München")
                .url(fullUrl);

        // Fetch the detail page and enrich the builder
        try {
            respectRateLimit();
            Document detailDoc = createConnection(fullUrl).referrer(BASE_URL).get();
            enrichFromDetailPage(builder, detailDoc);
        } catch (Exception e) {
            log.warn("Could not fetch detail page {} — using partial data: {}", fullUrl, e.getMessage());
        }

        return builder.build();
    }

    // ==================== Detail Page Parsing ====================

    /**
     * Parses a WG-Gesucht apartment detail page, populating all available fields:
     * title, price, rooms, sizeSqm, description, address, district, imageUrl, and availableFrom.
     * Fields that can't be found are left as they are on the builder.
     */
    void enrichFromDetailPage(ScrapedListingDto.ScrapedListingDtoBuilder builder, Document doc) {
        Element h1 = doc.selectFirst("h1.detailed-view-title, h1");
        if (h1 != null && !h1.text().isBlank()) {
            builder.title(h1.text().trim());
        }

        parseKeyFacts(doc, builder);

        Element descEl = doc.selectFirst("#ad_description_text, div[id^=freitext]");
        if (descEl != null && !descEl.text().isBlank()) {
            builder.description(descEl.text().trim());
        }

        String address = parseAddress(doc);
        if (address != null) {
            builder.address(address);
            String district = districtFromAddress(address);
            if (district != null) builder.district(district);
        }

        String imageUrl = parseImageUrl(doc);
        if (imageUrl != null) builder.imageUrl(imageUrl);

        LocalDate availableFrom = parseDateText(panelValue(doc, "frei ab"));
        if (availableFrom != null) builder.availableFrom(availableFrom);
    }

    /**
     * Extracts price, room count, and size.
     *
     * <p>The primary source is the key-facts bar at the top of the listing:</p>
     * <pre>
     * &lt;span class="key_fact_detail"&gt;Gesamtmiete&lt;/span&gt; ... &lt;b class="key_fact_value"&gt;1050€&lt;/b&gt;
     * </pre>
     * <p>If the total rent is missing there, the "Miete:" row of the costs panel is used.</p>
     */
    private void parseKeyFacts(Document doc, ScrapedListingDto.ScrapedListingDtoBuilder builder) {
        BigDecimal price = null;
        BigDecimal rooms = null;
        Integer    size  = null;

        for (Element label : doc.select(".key_fact_detail")) {
            Element container = label.parent();
            Element valueEl = container != null ? container.selectFirst(".key_fact_value") : null;
            if (valueEl == null) continue;

            String key   = label.text().toLowerCase(Locale.GERMAN);
            String value = valueEl.text().trim();

            if (key.contains("miete"))       price = parsePrice(value);
            else if (key.contains("zimmer")) rooms = parseRooms(value);
            else if (key.contains("größe"))  size  = parseSizeSqm(value);
        }

        if (price == null) {
            price = parsePrice(panelValue(doc, "miete"));
        }

        if (price != null) builder.price(price);
        if (rooms != null) builder.rooms(rooms);
        if (size  != null) builder.sizeSqm(size);

        log.debug("Key facts parsed — price={}, rooms={}, size={}", price, rooms, size);
    }

    /**
     * Returns the value of a label/value row in the detail panels, e.g.
     * {@code <span class="section_panel_detail">frei ab:</span> ... <span class="section_panel_value">01.10.2026</span>}.
     *
     * @param labelPrefix case-insensitive prefix of the label text
     * @return the trimmed value text, or null if no such row exists
     */
    private String panelValue(Document doc, String labelPrefix) {
        for (Element label : doc.select(".section_panel_detail")) {
            if (!label.text().toLowerCase(Locale.GERMAN).startsWith(labelPrefix)) continue;

            Element row = label.closest(".row");
            Element valueEl = row != null ? row.selectFirst(".section_panel_value") : null;
            if (valueEl != null && !valueEl.text().isBlank()) {
                return valueEl.text().trim();
            }
        }
        return null;
    }

    /**
     * Extracts the address from the "Adresse" panel, joining its lines with ", "
     * (e.g. "Grafinger Straße, 81671 München Berg am Laim"). Returns null if absent.
     */
    private String parseAddress(Document doc) {
        Element heading = doc.selectFirst("h2:containsOwn(Adresse)");
        if (heading == null || heading.parent() == null) return null;

        Element addressEl = heading.parent().selectFirst(".section_panel_detail");
        if (addressEl == null) return null;

        String address = addressEl.textNodes().stream()
                .map(node -> node.text().trim())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining(", "));
        return address.isBlank() ? null : address;
    }

    /**
     * Returns the listing's main image, preferring the Open Graph image.
     */
    private String parseImageUrl(Document doc) {
        Element og = doc.selectFirst("meta[property=og:image]");
        if (og != null && !og.attr("content").isBlank()) return og.attr("content");

        Element img = doc.selectFirst("img.sp-image");
        if (img != null) {
            String src = img.attr("src").isBlank() ? img.attr("data-src") : img.attr("src");
            if (!src.isBlank()) return src;
        }
        return null;
    }

    // ==================== Parsing Helpers ====================

    /**
     * Parses a German-format date string, handling "sofort" and "DD.MM.YYYY".
     */
    private LocalDate parseDateText(String text) {
        if (text == null || text.isBlank()) return null;

        if (text.trim().toLowerCase().contains("sofort")) return LocalDate.now();

        Matcher m = DATE_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return LocalDate.parse(m.group(1), WG_DATE_FORMAT);
            } catch (DateTimeParseException e) {
                log.debug("Could not parse date from: '{}'", text);
            }
        }
        return null;
    }

    /**
     * Returns the district named after the postcode in a WG-Gesucht address, keeping
     * compound names whole ("Au-Haidhausen" → "Au Haidhausen"). Falls back to
     * {@link #extractDistrict(String)} when the address has no "PLZ München District" part.
     */
    static String districtFromAddress(String address) {
        if (address == null) return null;
        Matcher m = ADDRESS_DISTRICT_PATTERN.matcher(address.trim());
        if (m.find() && !m.group(1).isBlank()) {
            return m.group(1).replace("-", " ").trim();
        }
        return extractDistrict(address);
    }

    /**
     * Finds a known Munich district in an address, matching whole words only
     * (so "Augustenstraße" doesn't match "Au"). Longer names win, so
     * "Berg am Laim" is preferred over "Laim".
     */
    static String extractDistrict(String address) {
        if (address == null || address.isBlank()) return null;
        String normalized = " " + address.toLowerCase(Locale.GERMAN).replaceAll("[^\\p{L}\\d]+", " ") + " ";
        for (String district : MUNICH_DISTRICTS) {
            if (normalized.contains(" " + district.toLowerCase(Locale.GERMAN) + " ")) return district;
        }
        return null;
    }
}
