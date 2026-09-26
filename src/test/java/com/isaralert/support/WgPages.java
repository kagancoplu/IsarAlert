package com.isaralert.support;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Builds HTML pages in WG-Gesucht's markup (as of 2026-09) for {@link FakeWgGesucht}.
 * Keep in sync with {@code src/test/resources/wg-gesucht/} and the scraper's selectors.
 */
public final class WgPages {

    private WgPages() {
    }

    /** A search results page linking to the given detail page paths (in order). */
    public static String searchPage(String... detailPaths) {
        String cards = Arrays.stream(detailPaths)
                .map(path -> """
                        <div class="wgg_card offer_list_item">
                            <h2 class="truncate_title"><a href="%s">Listing %s</a></h2>
                        </div>""".formatted(path, path))
                .collect(Collectors.joining("\n"));
        return """
                <!DOCTYPE html>
                <html lang="de"><head><meta charset="utf-8"><title>Wohnungen in München</title></head>
                <body><div id="main_column">
                %s
                </div></body></html>""".formatted(cards);
    }

    /** A detail page builder with sensible defaults; override what the test cares about. */
    public static Detail detail() {
        return new Detail();
    }

    public static final class Detail {
        private String title = "Schöne Wohnung";
        private String totalRent = "1.200&euro;";
        private String rooms = "2";
        private String size = "50m&sup2;";
        private String street = "Musterstraße 1";
        private String postcodeAndDistrict = "80331 München Altstadt-Lehel";
        private String freeFrom = "01.11.2026";
        private String description = "Helle Wohnung, frisch renoviert.";

        public Detail title(String title) { this.title = title; return this; }
        public Detail rent(int euros) { this.totalRent = euros + "&euro;"; return this; }
        public Detail rooms(String rooms) { this.rooms = rooms; return this; }
        public Detail size(int sqm) { this.size = sqm + "m&sup2;"; return this; }
        public Detail address(String street, String postcodeAndDistrict) {
            this.street = street;
            this.postcodeAndDistrict = postcodeAndDistrict;
            return this;
        }
        public Detail freeFrom(String date) { this.freeFrom = date; return this; }
        public Detail description(String description) { this.description = description; return this; }

        public String html() {
            return """
                    <!DOCTYPE html>
                    <html lang="de"><head><meta charset="utf-8">
                    <meta property="og:image" content="https://img.example/listing.jpg"/></head>
                    <body>
                    <div class="panel section_panel">
                        <h1 class="detailed-view-title"><span>%s</span></h1>
                        <div class="section_footer_dark"><div class="row"><div class="col-xs-12">
                            <div class="col-xs-4"><span class="key_fact_detail"> Größe </span><b class="key_fact_value"> %s </b></div>
                            <div class="col-xs-4"><span class="key_fact_detail">Gesamtmiete</span><b class="key_fact_value"> %s </b></div>
                            <div class="col-xs-4"><span class="key_fact_detail"> Zimmer </span><b class="key_fact_value"> %s </b></div>
                        </div></div></div>
                    </div>
                    <div class="panel section_panel"><div class="row">
                        <div class="col-xs-12 col-sm-6">
                            <h2 class="section_panel_title"> Adresse </h2>
                            <div class="row"><div class="col-xs-12">
                                <a href="#map_container"><span class="section_panel_detail"> %s <br/> %s </span></a>
                            </div></div>
                        </div>
                        <div class="col-xs-12 col-sm-6">
                            <h2 class="section_panel_title"> Verfügbarkeit </h2>
                            <div class="row">
                                <div class="col-xs-6"><span class="section_panel_detail">frei ab:</span></div>
                                <div class="col-xs-6"><span class="section_panel_value"> %s </span></div>
                            </div>
                        </div>
                    </div></div>
                    <div id="ad_description_text"><div id="freitext_0"><p>%s</p></div></div>
                    </body></html>""".formatted(title, size, totalRent, rooms, street, postcodeAndDistrict,
                    freeFrom, description);
        }
    }
}
